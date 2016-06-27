package crazypants.enderio.conduit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import appeng.api.WorldCoord;
import appeng.api.me.util.IGridInterface;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.packet.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.liquids.ILiquidTank;
import net.minecraftforge.liquids.LiquidStack;
import buildcraft.api.power.IPowerProvider;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import crazypants.enderio.EnderIO;
import crazypants.enderio.PacketHandler;
import crazypants.enderio.conduit.geom.CollidableCache;
import crazypants.enderio.conduit.geom.CollidableComponent;
import crazypants.enderio.conduit.geom.ConduitConnectorType;
import crazypants.enderio.conduit.geom.ConduitGeometryUtil;
import crazypants.enderio.conduit.geom.Offset;
import crazypants.enderio.conduit.geom.Offsets;
import crazypants.enderio.conduit.liquid.ILiquidConduit;
import crazypants.enderio.conduit.me.IMeConduit;
import crazypants.enderio.conduit.power.IPowerConduit;
import crazypants.enderio.power.MutablePowerProvider;
import crazypants.util.BlockCoord;

public class TileConduitBundle extends TileEntity implements IConduitBundle {

  private final List<IConduit> conduits = new ArrayList<IConduit>();

  private int facadeId = -1;
  private int facadeMeta = 0;

  private boolean facadeChanged;

  private final List<CollidableComponent> cachedCollidables = new ArrayList<CollidableComponent>();

  private boolean conduitsDirty = true;
  private boolean collidablesDirty = true;

  private int lightOpacity = 0;

  @SideOnly(Side.CLIENT)
  private FacadeRenderState facadeRenderAs;

  public TileConduitBundle() {
    blockType = EnderIO.blockConduitBundle;
  }

  @Override
  public void dirty() {
    conduitsDirty = true;
    collidablesDirty = true;
  }

  @Override
  public void writeToNBT(NBTTagCompound nbtRoot) {
    super.writeToNBT(nbtRoot);

    NBTTagList conduitTags = new NBTTagList();
    for (IConduit conduit : conduits) {
      NBTTagCompound conduitRoot = new NBTTagCompound();
      ConduitUtil.writeToNBT(conduit, conduitRoot);
      conduitTags.appendTag(conduitRoot);
    }
    nbtRoot.setTag("conduits", conduitTags);
    nbtRoot.setInteger("facadeId", facadeId);
    nbtRoot.setInteger("facadeMeta", facadeMeta);
  }

  @Override
  public void readFromNBT(NBTTagCompound nbtRoot) {
    super.readFromNBT(nbtRoot);

    conduits.clear();
    NBTTagList conduitTags = nbtRoot.getTagList("conduits");
    for (int i = 0; i < conduitTags.tagCount(); i++) {
      NBTTagCompound conduitTag = (NBTTagCompound) conduitTags.tagAt(i);
      IConduit conduit = ConduitUtil.readConduitFromNBT(conduitTag);
      if (conduit != null) {
        conduit.setBundle(this);
        conduits.add(conduit);
      }
    }
    facadeId = nbtRoot.getInteger("facadeId");
    facadeMeta = nbtRoot.getInteger("facadeMeta");

  }

  @Override
  public boolean hasFacade() {
    return facadeId > 0;
  }

  @Override
  public void setFacadeId(int blockID, boolean triggerUpdate) {
    this.facadeId = blockID;
    if (triggerUpdate) {
      facadeChanged = true;
    }
  }

  @Override
  public void setFacadeId(int blockID) {
    setFacadeId(blockID, true);
  }

  @Override
  public int getFacadeId() {
    return facadeId;
  }

  @Override
  public void setFacadeMetadata(int meta) {
    facadeMeta = meta;
  }

  @Override
  public int getFacadeMetadata() {
    return facadeMeta;
  }

  @Override
  @SideOnly(Side.CLIENT)
  public FacadeRenderState getFacadeRenderedAs() {
    if (facadeRenderAs == null) {
      facadeRenderAs = FacadeRenderState.NONE;
    }
    return facadeRenderAs;
  }

  @Override
  @SideOnly(Side.CLIENT)
  public void setFacadeRenderAs(FacadeRenderState state) {
    this.facadeRenderAs = state;
  }

  @Override
  public int getLightOpacity() {
    return lightOpacity;
  }

  @Override
  public void setLightOpacity(int opacity) {
    lightOpacity = opacity;
  }

  @Override
  public Packet getDescriptionPacket() {
    return PacketHandler.getPacket(this);
  }

  @Override
  public void onChunkUnload() {
    for (IConduit conduit : conduits) {
      conduit.onChunkUnload(worldObj);
    }
  }

  @Override
  public void updateEntity() {
    for (IConduit conduit : conduits) {
      conduit.updateEntity(worldObj);
    }

    if (worldObj != null && !worldObj.isRemote && conduitsDirty) {
      worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
      conduitsDirty = false;
    }

    if (worldObj != null && facadeChanged) {
      worldObj.markBlockForRenderUpdate(xCoord, yCoord, zCoord);
      facadeChanged = false;
    }
  }

  @Override
  public WorldCoord getLocation() {
    return new WorldCoord(xCoord, yCoord, zCoord);
  }

  @Override
  public void onNeighborBlockChange(int blockId) {
    boolean needsUpdate = false;
    for (IConduit conduit : conduits) {
      needsUpdate |= conduit.onNeighborBlockChange(blockId);
    }
    if (needsUpdate) {
      dirty();
    }
  }

  @Override
  public TileConduitBundle getEntity() {
    return this;
  }

  @Override
  public boolean hasType(Class<? extends IConduit> type) {
    return getConduit(type) != null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends IConduit> T getConduit(Class<T> type) {
    if (type == null) {
      return null;
    }
    for (IConduit conduit : conduits) {
      if (type.isInstance(conduit)) {
        return (T) conduit;
      }
    }
    return null;
  }

  @Override
  public void addConduit(IConduit conduit) {
    if (worldObj.isRemote) {
      return;
    }
    conduits.add(conduit);
    conduit.setBundle(this);
    conduit.onAddedToBundle();
    dirty();
  }

  @Override
  public void removeConduit(IConduit conduit) {
    if (conduit != null) {
      removeConduit(conduit, true);
    }
  }

  public void removeConduit(IConduit conduit, boolean notify) {
    if (worldObj.isRemote) {
      return;
    }
    conduit.onRemovedFromBundle();
    conduits.remove(conduit);
    conduit.setBundle(null);
    if (notify) {
      dirty();
    }
  }

  @Override
  public void onBlockRemoved() {
    if (worldObj.isRemote) {
      return;
    }
    List<IConduit> copy = new ArrayList<IConduit>(conduits);
    for (IConduit con : copy) {
      removeConduit(con, false);
    }
    dirty();
  }

  @Override
  public Collection<IConduit> getConduits() {
    return conduits;
  }

  @Override
  public Set<ForgeDirection> getConnections(Class<? extends IConduit> type) {
    IConduit con = getConduit(type);
    if (con != null) {
      return con.getConduitConnections();
    }
    return null;
  }

  @Override
  public boolean containsConnection(Class<? extends IConduit> type, ForgeDirection dir) {
    IConduit con = getConduit(type);
    if (con != null) {
      return con.containsConduitConnection(dir);
    }
    return false;
  }

  @Override
  public boolean containsConnection(ForgeDirection dir) {
    for (IConduit con : conduits) {
      if (con.containsConduitConnection(dir)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Set<ForgeDirection> getAllConnections() {
    Set<ForgeDirection> result = new HashSet<ForgeDirection>();
    for (IConduit con : conduits) {
      result.addAll(con.getConduitConnections());
    }
    return result;
  }

  // Geometry

  @Override
  public Offset getOffset(Class<? extends IConduit> type, ForgeDirection dir) {

    if (getConnectionCount(dir) < 2) {
      return Offset.NONE;
    }

    if (dir == ForgeDirection.UNKNOWN) {
      if (containsOnlySingleVerticalConnections()) {
        return Offsets.get(type, true, false);
      }
      if (containsOnlySingleHorizontalConnections()) {
        return Offsets.get(type, false, true);
      }
      return Offsets.get(type, true, true);
    }

    boolean isVertical = dir == ForgeDirection.UP || dir == ForgeDirection.DOWN;
    if (isVertical) {
      return Offsets.get(type, false, true);
    }
    return Offsets.get(type, true, false);
  }

  @Override
  public List<CollidableComponent> getCollidableComponents() {

    for (IConduit con : conduits) {
      collidablesDirty = collidablesDirty || con.haveCollidablesChangedSinceLastCall();
    }
    if (!collidablesDirty && !cachedCollidables.isEmpty()) {
      return cachedCollidables;
    }
    cachedCollidables.clear();
    for (IConduit conduit : conduits) {
      cachedCollidables.addAll(conduit.getCollidableComponents());
    }

    addConnectors(cachedCollidables);

    collidablesDirty = false;

    return cachedCollidables;
  }

  @Override
  public List<CollidableComponent> getConnectors() {
    List<CollidableComponent> result = new ArrayList<CollidableComponent>();
    addConnectors(result);
    return result;
  }

  private void addConnectors(List<CollidableComponent> result) {
    if (conduits.isEmpty()) {
      return;
    }
    CollidableCache cc = CollidableCache.instance;
    if (conduits.size() == 1) {

      IConduit con = conduits.get(0);
      result.addAll(cc.getCollidables(cc.createKey(con.getBaseConduitType(), Offset.NONE, ForgeDirection.UNKNOWN, false), con));

    } else if (containsOnlySingleVerticalConnections()) {

      if (allDirectionsHaveSameConnectionCount()) {
        for (IConduit con : conduits) {
          Class<? extends IConduit> type = con.getBaseConduitType();
          result.addAll(cc.getCollidables(cc.createKey(type, getOffset(type, ForgeDirection.UNKNOWN), ForgeDirection.UNKNOWN, false), con));
        }
      } else {
        // vertical box
        result.add(new CollidableComponent(null, ConduitGeometryUtil.instance.getBoundingBox(ConduitConnectorType.VERTICAL), ForgeDirection.UNKNOWN,
            ConduitConnectorType.VERTICAL));
      }

    } else if (containsOnlySingleHorizontalConnections()) {

      if (allDirectionsHaveSameConnectionCount()) {
        for (IConduit con : conduits) {
          Class<? extends IConduit> type = con.getBaseConduitType();
          result.addAll(cc.getCollidables(cc.createKey(type, getOffset(type, ForgeDirection.UNKNOWN), ForgeDirection.UNKNOWN, false), con));
        }

      } else {
        // vertical box
        result
            .add(new CollidableComponent(null, ConduitGeometryUtil.instance.getBoundingBox(ConduitConnectorType.HORIZONTAL), ForgeDirection.UNKNOWN,
                ConduitConnectorType.HORIZONTAL));
      }
    } else {
      result.add(new CollidableComponent(null, ConduitGeometryUtil.instance.getBoundingBox(ConduitConnectorType.BOTH), ForgeDirection.UNKNOWN,
          ConduitConnectorType.BOTH));
    }

  }

  private boolean containsOnlySingleVerticalConnections() {
    return getConnectionCount(ForgeDirection.UP) < 2 && getConnectionCount(ForgeDirection.DOWN) < 2;
  }

  private boolean containsOnlySingleHorizontalConnections() {
    return getConnectionCount(ForgeDirection.WEST) < 2 && getConnectionCount(ForgeDirection.EAST) < 2 &&
        getConnectionCount(ForgeDirection.NORTH) < 2 && getConnectionCount(ForgeDirection.SOUTH) < 2;
  }

  private boolean allDirectionsHaveSameConnectionCount() {
    for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
      boolean hasCon = conduits.get(0).isConnectedTo(dir);
      for (int i = 1; i < conduits.size(); i++) {
        if (hasCon != conduits.get(i).isConnectedTo(dir)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean containsOnlyHorizontalConnections() {
    for (IConduit con : conduits) {
      for (ForgeDirection dir : con.getConduitConnections()) {
        if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) {
          return false;
        }
      }
      for (ForgeDirection dir : con.getExternalConnections()) {
        if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) {
          return false;
        }
      }
    }
    return true;
  }

  private int getConnectionCount(ForgeDirection dir) {
    if (dir == ForgeDirection.UNKNOWN) {
      return conduits.size();
    }
    int result = 0;
    for (IConduit con : conduits) {
      if (con.containsConduitConnection(dir) || con.containsExternalConnection(dir)) {
        result++;
      }
    }
    return result;
  }

  // ------------ Power -----------------------------

  @Override
  public void setPowerProvider(IPowerProvider provider) {
  }

  @Override
  public IPowerProvider getPowerProvider() {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if (pc != null) {
      return pc.getPowerHandler();
    }
    return null;
  }

  @Override
  public void doWork() {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if (pc != null) {
      pc.doWork();
    }
  }

  @Override
  public int powerRequest(ForgeDirection from) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if (pc != null) {
      return pc.powerRequest(from);
    }
    return 0;
  }

  @Override
  public void applyPerdition() {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if (pc != null) {
      pc.applyPerdition();
    }

  }

  @Override
  public MutablePowerProvider getPowerHandler() {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if (pc != null) {
      return pc.getPowerHandler();
    }
    return null;
  }

  // ------- Liquids -----------------------------

  @Override
  public int fill(ForgeDirection from, LiquidStack resource, boolean doFill) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if (lc != null) {
      return lc.fill(from, resource, doFill);
    }
    return 0;
  }

  @Override
  public int fill(int tankIndex, LiquidStack resource, boolean doFill) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if (lc != null) {
      return lc.fill(tankIndex, resource, doFill);
    }
    return 0;
  }

  @Override
  public LiquidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if (lc != null) {
      return lc.drain(from, maxDrain, doDrain);
    }
    return null;
  }

  @Override
  public LiquidStack drain(int tankIndex, int maxDrain, boolean doDrain) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if (lc != null) {
      return lc.drain(tankIndex, maxDrain, doDrain);
    }
    return null;
  }

  @Override
  public ILiquidTank[] getTanks(ForgeDirection direction) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if (lc != null) {
      return lc.getTanks(direction);
    }
    return null;
  }

  @Override
  public ILiquidTank getTank(ForgeDirection direction, LiquidStack type) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if (lc != null) {
      return lc.getTank(direction, type);
    }
    return null;
  }

  @Override
  public float getPowerDrainPerTick() {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      return ic.getPowerDrainPerTick();
    }
    return 0;
  }

  @Override
  public void setNetworkReady(boolean isReady) {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      ic.setNetworkReady(isReady);
    }
  }

  @Override
  public boolean isMachineActive() {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      return ic.isMachineActive();
    }
    return false;
  }

  @Override
  public boolean isValid() {
    return getConduit(IMeConduit.class) != null;
  }

  @Override
  public void setPowerStatus(boolean hasPower) {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      ic.setPoweredStatus(hasPower);
    }
  }

  @Override
  public boolean isPowered() {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      return ic.isPowered();
    }
    return false;
  }

  @Override
  public IGridInterface getGrid() {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      return ic.getGrid();
    }
    return null;
  }

  @Override
  public void setGrid(IGridInterface gi) {
    IMeConduit ic = getConduit(IMeConduit.class);
    if(ic != null) {
      ic.setGrid(gi);
    }
  }

  @Override
  public World getWorld() {
    return worldObj;
  }
}
