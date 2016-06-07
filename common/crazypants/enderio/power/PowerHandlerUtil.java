package crazypants.enderio.power;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.ForgeDirection;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.transport.IPipe;
import buildcraft.api.transport.IPipeTile;

public class PowerHandlerUtil {

  public static boolean canConnectRecievePower(IPowerReceptor rec) {
    if(rec == null) {
      return false;
    }
    if(rec instanceof IPipeTile) {
      IPipeTile pipeTile = (IPipeTile) rec;
      IPipe pipe = pipeTile.getPipe();
      // ystem.out.println("PowerHandlerUtil.canConnectRecievePower: pipe is " +
      // pipe.getClass());
      return pipe.getClass().getName().contains("PipePower");

    }
    return true;

  }

  public static float getStoredEnergyForItem(ItemStack item) {
    NBTTagCompound tag = item.getTagCompound();
    if(tag == null) {
      return 0;
    }
    return tag.getFloat("storedEnergy");
  }

  public static void setStoredEnergyForItem(ItemStack item, float storedEnergy) {
    NBTTagCompound tag = item.getTagCompound();
    if(tag == null) {
      tag = new NBTTagCompound();
    }
    tag.setFloat("storedEnergy", storedEnergy);
    item.setTagCompound(tag);
  }

  public static EnderPowerProvider createHandler(ICapacitor capacitor) {
    EnderPowerProvider ph = new EnderPowerProvider();
    ph.configure(0, capacitor.getMinEnergyReceived(), capacitor.getMaxEnergyReceived(), capacitor.getMinActivationEnergy(), capacitor.getMaxEnergyStored());
    ph.configurePowerPerdition(0, 0);
    // ph.setPerdition(new NullPerditionCalculator());
    return ph;
  }

  public static void configure(EnderPowerProvider ph, ICapacitor capacitor) {
    ph.configure(0, capacitor.getMinEnergyReceived(), capacitor.getMaxEnergyReceived(), capacitor.getMinActivationEnergy(), capacitor.getMaxEnergyStored());
    if(ph.getEnergyStored() > ph.getMaxEnergyStored()) {
      ph.setEnergy(ph.getMaxEnergyStored());
    }
    ph.configurePowerPerdition(0, 0);
  }

  public static float transmitInternal(IInternalPowerReceptor receptor, float quantity, ForgeDirection from) {

    MutablePowerProvider ph = receptor.getPowerHandler();
    if(ph == null) {
      return 0;
    }

    float energyStored = ph.getEnergyStored();
    float canUse = quantity;
    canUse = Math.min(canUse, ph.getMaxEnergyReceived());
    // Don't overflow it
    canUse = Math.min(canUse, ph.getMaxEnergyStored() - energyStored);
    if(canUse < ph.getMinEnergyReceived()) {
      return 0;
    }

    ph.receiveEnergy(quantity, from);
    ph.setEnergy(energyStored);

    ph.setEnergy(energyStored + canUse);
    receptor.applyPerdition();

    return canUse;
  }

}