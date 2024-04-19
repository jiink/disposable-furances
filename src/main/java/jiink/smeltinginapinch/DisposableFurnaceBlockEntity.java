package jiink.smeltinginapinch;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;

import java.util.Optional;

public abstract class DisposableFurnaceBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory, ImplementedInventory, SidedInventory {

    public final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(2, ItemStack.EMPTY);
    private static int INPUT_SLOT = 0;
    private static int OUTPUT_SLOT = 1;

    protected final PropertyDelegate propertyDelegate;
    private int progress = 0;
    private int maxProgress;
    private int maxFuel;
    private int fuelRemaining;
    private int fuelStarted = 0; // 0 or 1

    public DisposableFurnaceBlockEntity(BlockPos pos, BlockState state, int numItemsCanSmelt, float howLongCanSmeltSec) {
        super(SmeltingInAPinch.WOODEN_FURNACE_BLOCK_ENTITY, pos, state);
        int howLongCanSmeltTicks = (int)(howLongCanSmeltSec * 20.0F);
        maxFuel = howLongCanSmeltTicks;
        fuelRemaining = maxFuel;
        maxProgress = howLongCanSmeltTicks / numItemsCanSmelt;
        this.propertyDelegate = new PropertyDelegate() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> DisposableFurnaceBlockEntity.this.progress;
                    case 1 -> DisposableFurnaceBlockEntity.this.maxProgress;
                    case 2 -> DisposableFurnaceBlockEntity.this.fuelRemaining;
                    case 3 -> DisposableFurnaceBlockEntity.this.fuelStarted;
                    case 4 -> DisposableFurnaceBlockEntity.this.maxFuel;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0 -> DisposableFurnaceBlockEntity.this.progress = value;
                    case 1 -> DisposableFurnaceBlockEntity.this.maxProgress = value;
                    case 2 -> DisposableFurnaceBlockEntity.this.fuelRemaining = value;
                    case 3 -> DisposableFurnaceBlockEntity.this.fuelStarted = value;
                    case 4 -> DisposableFurnaceBlockEntity.this.maxFuel = value;
                };
            }

            @Override
            public int size() {
                return 5;
            }
            
        };
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new DisposableFurnaceScreenHandler(syncId, playerInventory, this, this.propertyDelegate);
    }

    @Override
    public DefaultedList<ItemStack> getItems() {
        return inventory;
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(this.pos);
    }
    
    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        Inventories.writeNbt(nbt, inventory);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        Inventories.readNbt(nbt, inventory);
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient) {
            return;
        }
        if (fuelStarted == 1) {
            fuelRemaining--;
            if (!world.getBlockState(pos).get(Properties.LIT)) {
                world.setBlockState(pos, state.with(Properties.LIT, true));
            }
        }
        if (fuelRemaining <= 0) {
            burnoutDestroy(world, pos, state);
        }
        if (isOutputSlotEmptyOrReceivable()) {
            if (this.hasRecipe()) {
                if (fuelStarted == 0) {
                    fuelStarted = 1;
                }
                this.increaseCraftProgress();
                markDirty(world, pos, state);
                if (hasCraftingFinished()) {
                    this.craftItem();
                    this.resetProgress();
                }
            } else {
                this.resetProgress();
            }
        } else {
            this.resetProgress();
            markDirty(world, pos, state  );
        }
    }

    protected void burnoutDestroy(World world, BlockPos pos, BlockState state) {
        world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, pos, Block.getRawIdFromState(state));
        world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
    }

    private void resetProgress() {
        this.progress = 0;
    }

    protected boolean isSmeltable(ItemStack itemStack) {
        return this.world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, new SimpleInventory(itemStack), this.world).isPresent();
    }

    private ItemStack getResultItem(ItemStack input) {
        Optional<RecipeEntry<SmeltingRecipe>> recipeEntry = this.world.getRecipeManager()
                                                    .getFirstMatch(RecipeType.SMELTING, new SimpleInventory(input), this.world);
        if (recipeEntry.isPresent()) {
            return recipeEntry.get().value().craft(new SimpleInventory(input), null);
        } else {
            return ItemStack.EMPTY;
        }
    }

    private void craftItem() {
        ItemStack removedStack = this.removeStack(INPUT_SLOT, 1);
        //ItemStack result = new ItemStack(Items.IRON_INGOT);
        ItemStack result = getResultItem(removedStack);
        this.setStack(OUTPUT_SLOT, new ItemStack(result.getItem(), getStack(OUTPUT_SLOT).getCount() + result.getCount()));
    }

    private boolean hasCraftingFinished() {
        return progress >= maxProgress;
    }

    private void increaseCraftProgress() {
        progress++;
    }

    private boolean hasRecipe() {
        boolean hasInput = isSmeltable(getStack(INPUT_SLOT));
        if (!hasInput) {
            return false;
        }
        ItemStack result = getResultItem(getStack(INPUT_SLOT));
        return hasInput && canInsertAmountIntoOutputSlot(result) && canInsertItemIntoOutputSlot(result.getItem());
    }

    private boolean canInsertItemIntoOutputSlot(Item item) {
        return this.getStack(OUTPUT_SLOT).getItem() == item || this.getStack(OUTPUT_SLOT).isEmpty();
    }

    private boolean canInsertAmountIntoOutputSlot(ItemStack result) {
        return this.getStack(OUTPUT_SLOT).getCount() + result.getCount() <= getStack(OUTPUT_SLOT).getMaxCount();
    }

    private boolean isOutputSlotEmptyOrReceivable() {
        return this.getStack(OUTPUT_SLOT).isEmpty() || this.getStack(OUTPUT_SLOT).getCount() < this.getStack(OUTPUT_SLOT).getMaxCount();
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, Direction direction) {
        return slot == INPUT_SLOT;
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction direction) {
        return slot == OUTPUT_SLOT;
    }
}
