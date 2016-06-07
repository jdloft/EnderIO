package crazypants.enderio.machine.hypercube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.liquids.ILiquidTank;
import net.minecraftforge.liquids.ITankContainer;
import net.minecraftforge.liquids.LiquidStack;
import buildcraft.api.power.IPowerProvider;
import buildcraft.api.power.IPowerReceptor;
import crazypants.enderio.Config;
import crazypants.enderio.PacketHandler;
import crazypants.enderio.conduit.IConduitBundle;
import crazypants.enderio.conduit.liquid.ILiquidConduit;
import crazypants.enderio.machine.RedstoneControlMode;
import crazypants.enderio.power.BasicCapacitor;
import crazypants.enderio.power.EnderPowerProvider;
import crazypants.enderio.power.IInternalPowerReceptor;
import crazypants.enderio.power.MutablePowerProvider;
import crazypants.enderio.power.PowerHandlerUtil;
import crazypants.util.BlockCoord;
import crazypants.vecmath.VecmathUtil;

public class TileHyperCube extends TileEntity implements IInternalPowerReceptor, ITankContainer {

  private static final float ENERGY_LOSS = (float) Config.transceiverEnergyLoss;

  private static final float ENERGY_UPKEEP = (float) Config.transceiverUpkeepCost;

  private static final float MILLIBUCKET_TRANSMISSION_COST = (float) Config.transceiverBucketTransmissionCost / 1000F;

  private RedstoneControlMode inputControlMode = RedstoneControlMode.IGNORE;

  private RedstoneControlMode outputControlMode = RedstoneControlMode.IGNORE;

  private boolean powerOutputEnabled = true;

  private boolean powerInputEnabled = true;

  private final BasicCapacitor internalCapacitor = new BasicCapacitor(Config.transceiverMaxIO, 25000);

  EnderPowerProvider powerHandler;

  private float lastSyncPowerStored = 0;

  private final List<Receptor> receptors = new ArrayList<Receptor>();
  private ListIterator<Receptor> receptorIterator = receptors.listIterator();
  private boolean receptorsDirty = true;

  private final List<NetworkFluidHandler> fluidHandlers = new ArrayList<NetworkFluidHandler>();
  private boolean fluidHandlersDirty = true;

  private EnderPowerProvider disabledPowerHandler;

  private Channel channel = null;
  private Channel registeredChannel = null;
  private String owner;

  private boolean init = true;

  private float milliBucketsTransfered = 0;

  public TileHyperCube() {
    powerHandler = PowerHandlerUtil.createHandler(internalCapacitor);
  }

  public RedstoneControlMode getInputControlMode() {
    return inputControlMode;
  }

  public void setInputControlMode(RedstoneControlMode powerInputControlMode) {
    this.inputControlMode = powerInputControlMode;
  }

  public RedstoneControlMode getOutputControlMode() {
    return outputControlMode;
  }

  public void setOutputControlMode(RedstoneControlMode powerOutputControlMode) {
    this.outputControlMode = powerOutputControlMode;
  }

  public Channel getChannel() {
    return channel;
  }

  public void setChannel(Channel channel) {
    this.channel = channel;
  }

  int getEnergyStoredScaled(int scale) {
    return VecmathUtil.clamp(Math.round(scale * (powerHandler.getEnergyStored() / powerHandler.getMaxEnergyStored())), 0, scale);
  }

  public void onBreakBlock() {
    HyperCubeRegister.instance.deregister(this);
  }

  public void onBlockAdded() {
    HyperCubeRegister.instance.register(this);
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  private boolean isConnected() {
    if (channel == null || HyperCubeRegister.instance == null) {
      return false;
    }
    List<TileHyperCube> cons = HyperCubeRegister.instance.getCubesForChannel(channel);
    return cons != null && cons.size() > 1 && powerHandler.getEnergyStored() > 0;
  }

  private void balanceCubeNetworkEnergy() {

    List<TileHyperCube> cubes = HyperCubeRegister.instance.getCubesForChannel(channel);
    if (cubes == null || cubes.isEmpty()) {
      return;
    }
    float totalEnergy = 0;
    for (TileHyperCube cube : cubes) {
      totalEnergy += cube.powerHandler.getEnergyStored();
    }

    float energyPerNode = totalEnergy / cubes.size();
    float totalToTranfer = 0;
    for (TileHyperCube cube : cubes) {
      if (cube.powerHandler.getEnergyStored() < energyPerNode) {
        totalToTranfer += (energyPerNode - cube.powerHandler.getEnergyStored());
      }
    }

    float totalLoss = totalToTranfer * ENERGY_LOSS;
    totalEnergy -= totalLoss;
    totalEnergy = Math.max(0, totalEnergy);
    energyPerNode = totalEnergy / cubes.size();

    for (TileHyperCube cube : cubes) {
      boolean wasConnected = cube.isConnected();
      cube.powerHandler.setEnergy(energyPerNode);
      if (wasConnected != cube.isConnected()) {
        cube.fluidHandlersDirty = true;
      }
    }

  }

  @Override
  public void onChunkUnload() {
    if (HyperCubeRegister.instance != null) {
      HyperCubeRegister.instance.deregister(this);
    }
  }

  public void onNeighborBlockChange() {
    receptorsDirty = true;
    fluidHandlersDirty = true;
  }

  @Override
  public void updateEntity() {
    if (worldObj == null) { // sanity check
      return;
    }
    if (worldObj.isRemote) {
      return;
    } // else is server, do all logic only on the server

    // do the required tick to keep BC API happy
    float stored = powerHandler.getEnergyStored();
    powerHandler.update(this);

    boolean wasConnected = isConnected();

    // Pay upkeep cost
    stored -= ENERGY_UPKEEP;
    // Pay fluid transmission cost
    stored -= (MILLIBUCKET_TRANSMISSION_COST * milliBucketsTransfered);

    // update power status
    stored = Math.max(stored, 0);
    powerHandler.setEnergy(stored);

    milliBucketsTransfered = 0;

    powerInputEnabled = RedstoneControlMode.isConditionMet(inputControlMode, this);
    powerOutputEnabled = RedstoneControlMode.isConditionMet(outputControlMode, this);

    if (powerOutputEnabled) {
      transmitEnergy();
    }

    balanceCubeNetworkEnergy();

    // check we are still connected (i.e. we haven't run out of power or started
    // receiving power)
    boolean stillConnected = isConnected();
    if (wasConnected != stillConnected) {
      fluidHandlersDirty = true;
    }
    updateFluidHandlers();

    if (registeredChannel == null ? channel != null : !registeredChannel.equals(channel)) {
      if (registeredChannel != null) {
        HyperCubeRegister.instance.deregister(this, registeredChannel);
      }
      HyperCubeRegister.instance.register(this);
      registeredChannel = channel;
    }

    boolean requiresClientSync = wasConnected != stillConnected;

    float storedEnergy = powerHandler.getEnergyStored();
    // Update if our power has changed by more than 0.5%
    requiresClientSync |= lastSyncPowerStored != storedEnergy && worldObj.getTotalWorldTime() % 21 == 0;

    if (requiresClientSync) {
      lastSyncPowerStored = storedEnergy;
      // this will cause 'getPacketDescription()' to be called and its result
      // will be sent to the PacketHandler on the other end of
      // client/server connection
      worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
      // And this will make sure our current tile entity state is saved
      worldObj.updateTileEntityChunkAndDoNothing(xCoord, yCoord, zCoord, this);
    }

  }

  private boolean transmitEnergy() {

    if (powerHandler.getEnergyStored() <= 0) {
      return false;
    }
    float canTransmit = Math.min(powerHandler.getEnergyStored(), internalCapacitor.getMaxEnergyExtracted());
    float transmitted = 0;

    checkReceptors();

    if (!receptors.isEmpty() && !receptorIterator.hasNext()) {
      receptorIterator = receptors.listIterator();
    }

    int appliedCount = 0;
    int numReceptors = receptors.size();
    while (receptorIterator.hasNext() && canTransmit > 0 && appliedCount < numReceptors) {

      Receptor receptor = receptorIterator.next();
      IPowerProvider pp = receptor.receptor.getPowerProvider();
      if (pp != null && pp.getMinEnergyReceived() <= canTransmit && !powerHandler.isPowerSource(receptor.fromDir)) {
        float used;
        float boundCanTransmit = Math.min(canTransmit, pp.getMaxEnergyReceived());
        if (boundCanTransmit > 0) {
          if (receptor.receptor instanceof IInternalPowerReceptor) {
            used = PowerHandlerUtil.transmitInternal((IInternalPowerReceptor) receptor.receptor, canTransmit, receptor.fromDir.getOpposite());
          } else {
            used = Math.min(canTransmit, receptor.receptor.powerRequest(receptor.fromDir.getOpposite()));
            used = Math.min(used, pp.getMaxEnergyStored() - pp.getEnergyStored());
            pp.receiveEnergy(used, receptor.fromDir.getOpposite());
          }
          transmitted += used;
          canTransmit -= used;
        }
      }
      if (canTransmit <= 0) {
        break;
      }

      if (!receptors.isEmpty() && !receptorIterator.hasNext()) {
        receptorIterator = receptors.listIterator();
      }
      appliedCount++;
    }
    powerHandler.setEnergy(powerHandler.getEnergyStored() - transmitted);

    return transmitted > 0;

  }

  private EnderPowerProvider getDisabledPowerHandler() {
    if (disabledPowerHandler == null) {
      disabledPowerHandler = PowerHandlerUtil.createHandler(new BasicCapacitor(0, 0));
    }
    return disabledPowerHandler;
  }

  @Override
  public void setPowerProvider(IPowerProvider provider) {
  }

  @Override
  public IPowerProvider getPowerProvider() {
    if (powerInputEnabled) {
      return powerHandler;
    }
    return getDisabledPowerHandler();
  }

  @Override
  public void doWork() {
  }

  @Override
  public int powerRequest(ForgeDirection from) {
    if (powerInputEnabled) {
      return (int) Math.min(powerHandler.getMaxEnergyReceived(), powerHandler.getMaxEnergyStored() - powerHandler.getEnergyStored());
    }
    return 0;
  }

  @Override
  public MutablePowerProvider getPowerHandler() {
    return powerHandler;
  }

  @Override
  public void applyPerdition() {
  }

  private void checkReceptors() {
    if (!receptorsDirty) {
      return;
    }
    receptors.clear();
    BlockCoord myLoc = new BlockCoord(this);
    for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
      BlockCoord checkLoc = myLoc.getLocation(dir);
      TileEntity te = worldObj.getBlockTileEntity(checkLoc.x, checkLoc.y, checkLoc.z);
      if (te instanceof IPowerReceptor) {
        IPowerReceptor rec = (IPowerReceptor) te;
        receptors.add(new Receptor((IPowerReceptor) te, dir));
      }
    }

    receptorIterator = receptors.listIterator();
    receptorsDirty = false;
  }

  @Override
  public int fill(ForgeDirection from, LiquidStack resource, boolean doFill) {
    if (!powerInputEnabled) {
      return 0;
    }
    LiquidStack in = resource.copy();
    int result = 0;
    for (NetworkFluidHandler h : getNetworkHandlers()) {
      if (h.node.powerOutputEnabled) {
        if (h.handler instanceof IConduitBundle) {
          ILiquidConduit lc = ((IConduitBundle) h.handler).getConduit(ILiquidConduit.class);
          if (lc != null) {
            if (lc.getFluidType() == null || lc.getFluidType().isLiquidEqual(in)) {
              int filled = h.handler.fill(h.dirOp, in, doFill);
              in.amount -= filled;
              result += filled;
            }
          }
        } else {
          int filled = h.handler.fill(h.dirOp, in, doFill);
          in.amount -= filled;
          result += filled;
        }
      }
    }
    if (doFill) {
      milliBucketsTransfered += result;
    }
    return result;
  }

  @Override
  public int fill(int tankIndex, LiquidStack resource, boolean doFill) {
    return fill(ForgeDirection.UNKNOWN, resource, doFill);
  }

  @Override
  public LiquidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
    return drain(0, maxDrain, doDrain);
  }

  @Override
  public LiquidStack drain(int tankIndex, int maxDrainIn, boolean doDrain) {
    if (!powerOutputEnabled) {
      return null;
    }
    int maxDrain = maxDrainIn;
    LiquidStack result = null;
    for (NetworkFluidHandler h : getNetworkHandlers()) {
      if (h.node.powerInputEnabled) {
        LiquidStack res = h.handler.drain(h.dirOp, maxDrain, false);
        if (res != null) {
          if (result == null) {
            result = res.copy();
            if (doDrain) {
              h.handler.drain(h.dirOp, maxDrain, true);
            }
            maxDrain -= res.amount;
          } else if (result.isLiquidEqual(res)) {
            result.amount += res.amount;
            if (doDrain) {
              h.handler.drain(h.dirOp, maxDrain, true);
            }
            maxDrain -= res.amount;
          }
        }
      }
    }
    return result;
  }

  @Override
  public ILiquidTank[] getTanks(ForgeDirection direction) {
    List<ILiquidTank> res = new ArrayList<ILiquidTank>();
    for (NetworkFluidHandler h : getNetworkHandlers()) {
      ILiquidTank[] ti = h.handler.getTanks(h.dirOp);
      if (ti != null) {
        for (ILiquidTank t : ti) {
          if (t != null) {
            res.add(t);
          }
        }
      }
    }
    return res.toArray(new ILiquidTank[res.size()]);
  }

  @Override
  public ILiquidTank getTank(ForgeDirection direction, LiquidStack type) {
    List<ILiquidTank> res = new ArrayList<ILiquidTank>();
    for (NetworkFluidHandler h : getNetworkHandlers()) {
      ILiquidTank ti = h.handler.getTank(h.dirOp, type);
      if (ti != null) {
        return ti;
      }
    }
    return null;
  }

  private List<NetworkFluidHandler> getNetworkHandlers() {
    if (HyperCubeRegister.instance == null) {
      return Collections.emptyList();
    }
    List<TileHyperCube> cubes = HyperCubeRegister.instance.getCubesForChannel(channel);
    if (cubes == null || cubes.isEmpty()) {
      return Collections.emptyList();
    }
    List<NetworkFluidHandler> result = new ArrayList<NetworkFluidHandler>();
    for (TileHyperCube cube : cubes) {
      if (cube != this && cube != null) {
        List<NetworkFluidHandler> handlers = cube.fluidHandlers;
        if (handlers != null && !handlers.isEmpty()) {
          result.addAll(handlers);
        }
      }
    }
    return result;

  }

  private void updateFluidHandlers() {
    if (!fluidHandlersDirty) {
      return;
    }
    fluidHandlers.clear();
    if (isConnected()) {
      BlockCoord myLoc = new BlockCoord(this);
      for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
        BlockCoord checkLoc = myLoc.getLocation(dir);
        TileEntity te = worldObj.getBlockTileEntity(checkLoc.x, checkLoc.y, checkLoc.z);
        if (te instanceof ITankContainer) {
          ITankContainer fh = (ITankContainer) te;
          fluidHandlers.add(new NetworkFluidHandler(this, fh, dir));
        }
      }
    }
    fluidHandlersDirty = false;
  }

  @Override
  public void readFromNBT(NBTTagCompound nbtRoot) {
    super.readFromNBT(nbtRoot);
    powerHandler.setEnergy(nbtRoot.getFloat("storedEnergy"));
    inputControlMode = RedstoneControlMode.values()[nbtRoot.getShort("inputControlMode")];
    outputControlMode = RedstoneControlMode.values()[nbtRoot.getShort("outputControlMode")];

    String channelName = nbtRoot.getString("channelName");
    String channelUser = nbtRoot.getString("channelUser");
    if (channelName != null && !channelName.isEmpty()) {
      channel = new Channel(channelName, channelUser == null || channelUser.isEmpty() ? null : channelUser);
    } else {
      channel = null;
    }

    owner = nbtRoot.getString("owner");
  }

  @Override
  public void writeToNBT(NBTTagCompound nbtRoot) {
    super.writeToNBT(nbtRoot);
    nbtRoot.setFloat("storedEnergy", powerHandler.getEnergyStored());
    nbtRoot.setShort("inputControlMode", (short) inputControlMode.ordinal());
    nbtRoot.setShort("outputControlMode", (short) outputControlMode.ordinal());
    if (channel != null) {
      nbtRoot.setString("channelName", channel.name);
      if (channel.user != null) {
        nbtRoot.setString("channelUser", channel.user);
      }
    }
    if (owner != null && !(owner.isEmpty())) {
      nbtRoot.setString("owner", owner);
    }
  }

  @Override
  public Packet getDescriptionPacket() {
    return PacketHandler.getPacket(this);
  }

  static class Receptor {
    IPowerReceptor receptor;
    ForgeDirection fromDir;

    private Receptor(IPowerReceptor rec, ForgeDirection fromDir) {
      this.receptor = rec;
      this.fromDir = fromDir;
    }
  }

  static class NetworkFluidHandler {
    final TileHyperCube node;
    final ITankContainer handler;
    final ForgeDirection dir;
    final ForgeDirection dirOp;

    private NetworkFluidHandler(TileHyperCube node, ITankContainer handler, ForgeDirection dir) {
      this.node = node;
      this.handler = handler;
      this.dir = dir;
      dirOp = dir.getOpposite();
    }

  }

}