package cofh.thermal.lib.tileentity;

import cofh.core.network.packet.client.TileStatePacket;
import cofh.lib.energy.EnergyStorageCoFH;
import cofh.lib.fluid.FluidStorageCoFH;
import cofh.lib.inventory.ItemStorageCoFH;
import cofh.lib.util.TimeTracker;
import cofh.lib.util.Utils;
import cofh.lib.util.helpers.AugmentDataHelper;
import cofh.lib.util.helpers.MathHelper;
import cofh.lib.xp.XpStorage;
import cofh.thermal.lib.util.ThermalEnergyHelper;
import cofh.thermal.lib.util.recipes.IMachineInventory;
import cofh.thermal.lib.util.recipes.MachineProperties;
import cofh.thermal.lib.util.recipes.internal.IMachineRecipe;
import cofh.thermal.lib.util.recipes.internal.IRecipeCatalyst;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static cofh.core.util.helpers.FluidHelper.fluidsEqual;
import static cofh.lib.util.constants.Constants.ACTIVE;
import static cofh.lib.util.constants.Constants.BASE_CHANCE;
import static cofh.lib.util.constants.NBTTags.*;
import static cofh.lib.util.helpers.AugmentableHelper.*;
import static cofh.lib.util.helpers.ItemHelper.cloneStack;
import static cofh.lib.util.helpers.ItemHelper.itemsEqualWithTags;
import static cofh.thermal.lib.common.ThermalAugmentRules.MACHINE_NO_FLUID_VALIDATOR;
import static cofh.thermal.lib.common.ThermalAugmentRules.MACHINE_VALIDATOR;

public abstract class MachineTileProcess extends ReconfigurableTile4Way implements ITickableTileEntity, IMachineInventory {

    protected ItemStorageCoFH chargeSlot = new ItemStorageCoFH(1, ThermalEnergyHelper::hasEnergyHandlerCap);

    protected IMachineRecipe curRecipe;
    protected IRecipeCatalyst curCatalyst;
    protected List<Integer> itemInputCounts = new ArrayList<>();
    protected List<Integer> fluidInputCounts = new ArrayList<>();

    protected int process;
    protected int processMax;

    protected int baseProcessTick = getBaseProcessTick();
    protected int processTick = baseProcessTick;

    protected TimeTracker timeTracker = new TimeTracker();
    public boolean wasActive;

    public MachineTileProcess(TileEntityType<?> tileEntityTypeIn) {

        super(tileEntityTypeIn);
        energyStorage = new EnergyStorageCoFH(getBaseEnergyStorage(), getBaseEnergyXfer());
        xpStorage = new XpStorage(getBaseXpStorage());
    }

    @Override
    public void tick() {

        boolean curActive = isActive;
        if (isActive) {
            processTick();
            if (canProcessFinish()) {
                processFinish();
                transferOutput();
                transferInput();
                if (!redstoneControl.getState() || !canProcessStart()) {
                    energyStorage.modify(-process);     // Addresses case where additional process energy was spent, and another process does not immediately begin.
                    processOff();
                } else {
                    processStart();
                }
            } else if (energyStorage.getEnergyStored() < processTick) {
                processOff();
            }
        } else if (redstoneControl.getState()) {
            if (Utils.timeCheck(level)) {
                transferOutput();
                transferInput();
            }
            if (Utils.timeCheckQuarter(level) && canProcessStart()) {
                processStart();
                processTick();
                isActive = true;
            }
        }
        updateActiveState(curActive);
        chargeEnergy();
    }

    @Override
    protected void updateActiveState(boolean prevActive) {

        // If not active but WAS active this tick.
        if (!isActive && prevActive) {
            wasActive = true;
            if (level != null) {
                timeTracker.markTime(level);
            }
            return;
        }
        // Otherwise if IS active but was not, or WAS & delayed off OR Empty Tracker (Instant)
        if (prevActive != isActive || wasActive && (timeTracker.hasDelayPassed(level, 40) || timeTracker.notSet())) {
            // TODO: Config time delay
            wasActive = false;
            if (getBlockState().hasProperty(ACTIVE)) {
                level.setBlockAndUpdate(worldPosition, getBlockState().setValue(ACTIVE, isActive));
            }
            TileStatePacket.sendToClient(this);
        }
    }

    // region PROCESS
    protected boolean canProcessStart() {

        if (energyStorage.getEnergyStored() - process < processTick) {
            return false;
        }
        if (!validateInputs()) {
            return false;
        }
        return validateOutputs();
    }

    protected boolean canProcessFinish() {

        return process <= 0;
    }

    protected void processStart() {

        processTick = baseProcessTick;
        int energy = curRecipe.getEnergy(this);
        energy += process;                  // Apply extra energy to next process
        process = processMax = energy;
        if (cacheRenderFluid()) {
            TileStatePacket.sendToClient(this);
        }
    }

    protected void processFinish() {

        if (!validateInputs()) {
            processOff();
            return;
        }
        resolveOutputs();
        resolveInputs();
        markDirtyFast();
    }

    protected void processOff() {

        process = 0;
        isActive = false;
        wasActive = true;
        clearRecipe();
        if (level != null) {
            timeTracker.markTime(level);
        }
    }

    protected int processTick() {

        if (process <= 0) {
            return 0;
        }
        energyStorage.modify(-processTick);
        process -= processTick;
        return processTick;
    }
    // endregion

    // region HELPERS
    protected void chargeEnergy() {

        if (!chargeSlot.isEmpty()) {
            chargeSlot.getItemStack()
                    .getCapability(ThermalEnergyHelper.getBaseEnergySystem(), null)
                    .ifPresent(c -> energyStorage.receiveEnergy(c.extractEnergy(Math.min(energyStorage.getMaxReceive(), energyStorage.getSpace()), false), false));
        }
    }

    protected boolean cacheRecipe() {

        return false;
    }

    protected void clearRecipe() {

        curRecipe = null;
        curCatalyst = null;
        itemInputCounts = new ArrayList<>();
        fluidInputCounts = new ArrayList<>();
    }

    protected boolean validateInputs() {

        if (!cacheRecipe()) {
            return false;
        }
        List<? extends ItemStorageCoFH> slotInputs = inputSlots();
        for (int i = 0; i < slotInputs.size() && i < itemInputCounts.size(); ++i) {
            int inputCount = itemInputCounts.get(i);
            if (inputCount > 0 && slotInputs.get(i).getItemStack().getCount() < inputCount) {
                return false;
            }
        }
        List<? extends FluidStorageCoFH> tankInputs = inputTanks();
        for (int i = 0; i < tankInputs.size() && i < fluidInputCounts.size(); ++i) {
            int inputCount = fluidInputCounts.get(i);
            FluidStack input = tankInputs.get(i).getFluidStack();
            if (inputCount > 0 && (input.isEmpty() || input.getAmount() < inputCount)) {
                return false;
            }
        }
        return true;
    }

    protected boolean validateOutputs() {

        if (curRecipe == null && !cacheRecipe()) {
            return false;
        }
        // ITEMS
        List<? extends ItemStorageCoFH> slotOutputs = outputSlots();
        List<ItemStack> recipeOutputItems = curRecipe.getOutputItems(this);
        boolean[] used = new boolean[outputSlots().size()];

        for (int j = 0; j < recipeOutputItems.size(); ++j) {
            ItemStack recipeOutput = recipeOutputItems.get(j);

            boolean matched = false;
            for (int i = 0; i < slotOutputs.size(); ++i) {
                if (used[i]) {
                    continue;
                }
                ItemStack output = slotOutputs.get(i).getItemStack();
                if (output.getCount() >= output.getMaxStackSize()) {
                    continue;
                }
                if (itemsEqualWithTags(output, recipeOutput)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (int i = 0; i < slotOutputs.size(); ++i) {
                    if (used[i]) {
                        continue;
                    }
                    if (slotOutputs.get(i).isEmpty()) {
                        used[i] = true;
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched && (j == 0 || !secondaryNullFeature)) {
                return false;
            }
        }

        //        for (ItemStack recipeOutput : recipeOutputItems) {
        //            boolean matched = false;
        //            for (int i = 0; i < slotOutputs.size(); ++i) {
        //                if (used[i]) {
        //                    continue;
        //                }
        //                ItemStack output = slotOutputs.get(i).getItemStack();
        //                if (output.getCount() >= output.getMaxStackSize()) {
        //                    continue;
        //                }
        //                if (itemsEqualWithTags(output, recipeOutput)) {
        //                    used[i] = true;
        //                    matched = true;
        //                    break;
        //                }
        //            }
        //            if (!matched) {
        //                for (int i = 0; i < slotOutputs.size(); ++i) {
        //                    if (used[i]) {
        //                        continue;
        //                    }
        //                    if (slotOutputs.get(i).isEmpty()) {
        //                        used[i] = true;
        //                        matched = true;
        //                        break;
        //                    }
        //                }
        //            }
        //            if (!matched) {
        //                return false;
        //            }
        //        }

        // FLUIDS
        List<? extends FluidStorageCoFH> tankOutputs = outputTanks();
        List<FluidStack> recipeOutputFluids = curRecipe.getOutputFluids(this);
        used = new boolean[outputTanks().size()];
        for (FluidStack recipeOutput : recipeOutputFluids) {
            boolean matched = false;
            for (int i = 0; i < tankOutputs.size(); ++i) {
                FluidStack output = tankOutputs.get(i).getFluidStack();
                if (used[i] || tankOutputs.get(i).getSpace() < recipeOutput.getAmount()) {
                    continue;
                }
                if (fluidsEqual(output, recipeOutput)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (int i = 0; i < tankOutputs.size(); ++i) {
                    if (used[i]) {
                        continue;
                    }
                    if (tankOutputs.get(i).isEmpty()) {
                        used[i] = true;
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    protected void resolveOutputs() {

        List<ItemStack> recipeOutputItems = curRecipe.getOutputItems(this);
        List<FluidStack> recipeOutputFluids = curRecipe.getOutputFluids(this);
        List<Float> recipeOutputChances = curRecipe.getOutputItemChances(this);

        // Output Items
        for (int i = 0; i < recipeOutputItems.size(); ++i) {
            ItemStack recipeOutput = recipeOutputItems.get(i);
            float chance = recipeOutputChances.get(i);
            int recipeCount = recipeOutput.getCount();
            int outputCount = chance <= BASE_CHANCE ? recipeCount : (int) chance * recipeCount;

            if (MathHelper.RANDOM.nextFloat() < chance) {
                ItemStorageCoFH matchSlot = null;
                for (ItemStorageCoFH slot : outputSlots()) {
                    ItemStack output = slot.getItemStack();
                    if (itemsEqualWithTags(output, recipeOutput) && output.getCount() < output.getMaxStackSize()) {
                        output.grow(outputCount);
                        matchSlot = slot;
                        break;
                    }
                }
                if (matchSlot == null) {
                    for (ItemStorageCoFH slot : outputSlots()) {
                        if (slot.isEmpty()) {
                            slot.setItemStack(cloneStack(recipeOutput, outputCount));
                            matchSlot = slot;
                            break;
                        }
                    }
                }
                if (matchSlot != null && chance > BASE_CHANCE) {
                    chance -= (int) chance;
                    if (MathHelper.RANDOM.nextFloat() < chance) {
                        matchSlot.getItemStack().grow(recipeCount);
                    }
                }
            }
        }
        // Output Fluids
        for (FluidStack recipeOutput : recipeOutputFluids) {
            boolean matched = false;
            for (FluidStorageCoFH tank : outputTanks()) {
                FluidStack output = tank.getFluidStack();
                if (tank.getSpace() >= recipeOutput.getAmount() && fluidsEqual(output, recipeOutput)) {
                    output.setAmount(output.getAmount() + recipeOutput.getAmount());
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (FluidStorageCoFH tank : outputTanks()) {
                    if (tank.isEmpty()) {
                        tank.setFluidStack(recipeOutput.copy());
                        break;
                    }
                }
            }
        }
        // Xp
        xpStorage.receiveXPFloat(curRecipe.getXp(this), false);
    }

    protected void resolveInputs() {

        // Input Items
        for (int i = 0; i < itemInputCounts.size(); ++i) {
            inputSlots().get(i).consume(itemInputCounts.get(i));
        }
        // Input Fluids
        for (int i = 0; i < fluidInputCounts.size(); ++i) {
            inputTanks().get(i).modify(-fluidInputCounts.get(i));
        }
    }
    // endregion

    // region GUI
    @Override
    public int getCurSpeed() {

        return isActive ? processTick : 0;
    }

    @Override
    public int getMaxSpeed() {

        return baseProcessTick;
    }

    @Override
    public double getEfficiency() {

        if (getMachineProperties().getEnergyMod() <= 0) {
            return Double.MAX_VALUE;
        }
        return 1.0D / getMachineProperties().getEnergyMod();
    }

    @Override
    public int getScaledProgress(int scale) {

        if (!isActive || processMax <= 0 || process <= 0) {
            return 0;
        }
        return scale * (processMax - process) / processMax;
    }

    @Override
    public int getScaledSpeed(int scale) {

        if (!isActive) {
            return 0;
        }
        return MathHelper.clamp(scale * processTick / baseProcessTick, 1, scale);
    }
    // endregion

    // region NETWORK
    @Override
    public PacketBuffer getGuiPacket(PacketBuffer buffer) {

        super.getGuiPacket(buffer);

        buffer.writeInt(process);
        buffer.writeInt(processMax);
        buffer.writeInt(processTick);

        return buffer;
    }

    @Override
    public void handleGuiPacket(PacketBuffer buffer) {

        super.handleGuiPacket(buffer);

        process = buffer.readInt();
        processMax = buffer.readInt();
        processTick = buffer.readInt();
    }
    // endregion

    // region NBT
    @Override
    public void load(BlockState state, CompoundNBT nbt) {

        super.load(state, nbt);

        wasActive = nbt.getBoolean(TAG_ACTIVE_PREV);

        process = nbt.getInt(TAG_PROCESS);
        processMax = nbt.getInt(TAG_PROCESS_MAX);
        processTick = nbt.getInt(TAG_PROCESS_TICK);
    }

    @Override
    public CompoundNBT save(CompoundNBT nbt) {

        super.save(nbt);

        nbt.putBoolean(TAG_ACTIVE_PREV, wasActive);

        nbt.putInt(TAG_PROCESS, process);
        nbt.putInt(TAG_PROCESS_MAX, processMax);
        nbt.putInt(TAG_PROCESS_TICK, processTick);

        return nbt;
    }
    // endregion

    // region AUGMENTS
    protected MachineProperties machineProperties = new MachineProperties();
    protected boolean secondaryNullFeature = false;

    @Override
    protected Predicate<ItemStack> augValidator() {

        BiPredicate<ItemStack, List<ItemStack>> validator = tankInv.hasTanks() ? MACHINE_VALIDATOR : MACHINE_NO_FLUID_VALIDATOR;
        return item -> AugmentDataHelper.hasAugmentData(item) && validator.test(item, getAugmentsAsList());
    }

    @Override
    protected void resetAttributes() {

        super.resetAttributes();

        setAttribute(augmentNBT, TAG_AUGMENT_MACHINE_POWER, 1.0F);
        setAttribute(augmentNBT, TAG_AUGMENT_MACHINE_SPEED, 1.0F);

        machineProperties.resetAttributes();

        secondaryNullFeature = false;
    }

    @Override
    protected void setAttributesFromAugment(CompoundNBT augmentData) {

        super.setAttributesFromAugment(augmentData);

        setAttributeFromAugmentAdd(augmentNBT, augmentData, TAG_AUGMENT_MACHINE_POWER);
        setAttributeFromAugmentAdd(augmentNBT, augmentData, TAG_AUGMENT_MACHINE_SPEED);

        machineProperties.setAttributesFromAugment(augmentData);

        secondaryNullFeature |= getAttributeMod(augmentData, TAG_AUGMENT_FEATURE_SECONDARY_NULL) > 0;
    }

    @Override
    protected void finalizeAttributes(Map<Enchantment, Integer> enchantmentMap) {

        super.finalizeAttributes(enchantmentMap);
        float baseMod = getAttributeModWithDefault(augmentNBT, TAG_AUGMENT_BASE_MOD, 1.0F);
        float powerMod = getAttributeModWithDefault(augmentNBT, TAG_AUGMENT_MACHINE_POWER, 1.0F);
        float speedMod = getAttributeModWithDefault(augmentNBT, TAG_AUGMENT_MACHINE_SPEED, 1.0F);
        float totalMod = baseMod * powerMod * speedMod;

        machineProperties.finalizeAttributes();
        processTick = baseProcessTick = Math.round(getBaseProcessTick() * totalMod);
    }
    // endregion

    // region IMachineInventory
    public MachineProperties getMachineProperties() {

        return machineProperties;
    }
    // endregion

    // region ITileCallback
    @Override
    public void onInventoryChanged(int slot) {

        super.onInventoryChanged(slot);

        if (level != null && Utils.isServerWorld(level) && isActive) {
            if (slot >= invSize() - augSize()) {
                if (!validateOutputs()) {
                    processOff();
                }
            } else if (slot < inventory.getInputSlots().size()) {
                IMachineRecipe tempRecipe = curRecipe;
                IRecipeCatalyst tempCatalyst = curCatalyst;
                if (!validateInputs() || tempRecipe != curRecipe || tempCatalyst != curCatalyst) {
                    processOff();
                }
            }
        }
    }

    @Override
    public void onTankChanged(int tank) {

        if (Utils.isServerWorld(level) && tank < tankInv.getInputTanks().size()) {
            if (isActive) {
                IMachineRecipe tempRecipe = curRecipe;
                IRecipeCatalyst tempCatalyst = curCatalyst;
                if (!validateInputs() || tempRecipe != curRecipe || tempCatalyst != curCatalyst) {
                    processOff();
                }
            }
        }
        super.onTankChanged(tank);
    }
    // endregion
}
