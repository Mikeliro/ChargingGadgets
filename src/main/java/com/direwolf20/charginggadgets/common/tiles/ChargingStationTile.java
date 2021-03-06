package com.direwolf20.charginggadgets.common.tiles;

import com.direwolf20.charginggadgets.common.Config;
import com.direwolf20.charginggadgets.common.blocks.ModBlocks;
import com.direwolf20.charginggadgets.common.capabilities.ChargerEnergyStorage;
import com.direwolf20.charginggadgets.common.capabilities.ChargerItemHandler;
import com.direwolf20.charginggadgets.common.container.ChargingStationContainer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.item.BucketItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.IIntArray;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

// Todo: completely rewrite this class from the ground up
public class ChargingStationTile extends TileEntity implements ITickableTileEntity, INamedContainerProvider {
    public enum Slots {
        FUEL(0),
        CHARGE(1);

        int id;

        Slots(int number) {
            id = number;
        }

        public int getId() {
            return id;
        }
    }

    private int counter = 0;
    private int maxBurn = 0;

    public ChargerEnergyStorage energyStorage;
    private LazyOptional<ChargerEnergyStorage> energy;
    private LazyOptional<ItemStackHandler> inventory  = LazyOptional.of(() -> new ChargerItemHandler(this));

    // Handles tracking changes, kinda messy but apparently this is how the cool kids do it these days
    public final IIntArray chargingStationData = new IIntArray() {
        @Override
        public int get(int index) {
            switch (index) {
                case 0:
                    return ChargingStationTile.this.energyStorage.getEnergyStored() / 32;
                case 1:
                    return ChargingStationTile.this.energyStorage.getMaxEnergyStored() / 32;
                case 2:
                    return ChargingStationTile.this.counter;
                case 3:
                    return ChargingStationTile.this.maxBurn;
                default:
                    throw new IllegalArgumentException("Invalid index: " + index);
            }
        }

        @Override
        public void set(int index, int value) {
            throw new IllegalStateException("Cannot set values through IIntArray");
        }

        @Override
        public int size() {
            return 4;
        }
    };

    public ChargingStationTile() {
        super(ModBlocks.CHARGING_STATION_TILE.get());
        this.energyStorage = new ChargerEnergyStorage(this, 0, Config.GENERAL.chargerMaxPower.get());
        this.energy = LazyOptional.of(() -> this.energyStorage);
    }

    @Nullable
    @Override
    public Container createMenu(int i, PlayerInventory playerInventory, PlayerEntity playerEntity) {
        assert world != null;
        return new ChargingStationContainer(this, this.chargingStationData, i, playerInventory, this.inventory.orElse(new ItemStackHandler(2)));
    }

    @Override
    public void tick() {
        if (getWorld() == null)
            return;

        inventory.ifPresent(handler -> {
            tryBurn();

            ItemStack stack = handler.getStackInSlot(Slots.CHARGE.id);
            if (!stack.isEmpty())
                chargeItem(stack);
        });
    }

    private void chargeItem(ItemStack stack) {
        this.getCapability(CapabilityEnergy.ENERGY).ifPresent(energyStorage -> stack.getCapability(CapabilityEnergy.ENERGY).ifPresent(itemEnergy -> {
            if (!isChargingItem(itemEnergy))
                return;

            int energyRemoved = itemEnergy.receiveEnergy(Math.min(energyStorage.getEnergyStored(), 2500), false);
            ((ChargerEnergyStorage) energyStorage).consumeEnergy(energyRemoved, false);
        }));
    }

    public boolean isChargingItem(IEnergyStorage energy) {
        return energy.getEnergyStored() >= 0 && energy.receiveEnergy(energy.getEnergyStored(), true) >= 0;
    }

    private void tryBurn() {
        if( world == null )
            return;

        this.getCapability(CapabilityEnergy.ENERGY).ifPresent(energyStorage -> {
            boolean canInsertEnergy = energyStorage.receiveEnergy(625, true) > 0;
            if (counter > 0 && canInsertEnergy) {
                burn(energyStorage);
            } else if (canInsertEnergy) {
                if (initBurn())
                    burn(energyStorage);
            }
        });
    }


    private void burn(IEnergyStorage energyStorage) {
        energyStorage.receiveEnergy(625, false);

        counter--;
        if (counter == 0) {
            maxBurn = 0;
            initBurn();
        }
    }

    private boolean initBurn() {
        ItemStackHandler handler = inventory.orElseThrow(RuntimeException::new);
        ItemStack stack = handler.getStackInSlot(Slots.FUEL.id);

        int burnTime = ForgeHooks.getBurnTime(stack);
        if (burnTime > 0) {
            Item fuelStack = handler.getStackInSlot(Slots.FUEL.id).getItem();
            handler.extractItem(0, 1, false);
            if( fuelStack instanceof BucketItem && fuelStack != Items.BUCKET )
                handler.insertItem(0, new ItemStack(Items.BUCKET, 1), false);

            markDirty();
            counter = (int) Math.floor(burnTime) / 50;
            maxBurn = counter;
            return true;
        }
        return false;
    }

    @Override
    public void read(BlockState stateIn, CompoundNBT compound) {
        super.read(stateIn, compound);

        inventory.ifPresent(h -> h.deserializeNBT(compound.getCompound("inv")));
        energy.ifPresent(h -> h.deserializeNBT(compound.getCompound("energy")));
        counter = compound.getInt("counter");
        maxBurn = compound.getInt("maxburn");
    }

    @Override
    public CompoundNBT write(CompoundNBT compound) {
        inventory.ifPresent(h ->  compound.put("inv", h.serializeNBT()));
        energy.ifPresent(h -> compound.put("energy", h.serializeNBT()));

        compound.putInt("counter", counter);
        compound.putInt("maxburn", maxBurn);
        return super.write(compound);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, final @Nullable Direction side) {
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
            return inventory.cast();

        if (cap == CapabilityEnergy.ENERGY)
            return energy.cast();

        return super.getCapability(cap, side);
    }

    @Override
    public SUpdateTileEntityPacket getUpdatePacket() {
        // Vanilla uses the type parameter to indicate which type of tile entity (command block, skull, or beacon?) is receiving the packet, but it seems like Forge has overridden this behavior
        return new SUpdateTileEntityPacket(pos, 0, getUpdateTag());
    }

    @Override
    public CompoundNBT getUpdateTag() {
        return write(new CompoundNBT());
    }

    @Override
    public void handleUpdateTag(BlockState stateIn, CompoundNBT tag) {
        read(stateIn, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket pkt) {
        read(this.getBlockState(), pkt.getNbtCompound());
    }

    @Override
    public void remove() {
        energy.invalidate();
        inventory.invalidate();
        super.remove();
    }

    @Override
    public ITextComponent getDisplayName() {
        return new StringTextComponent("Charging Station Tile");
    }
}
