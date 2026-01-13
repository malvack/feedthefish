package cc.martintech.feedthefish;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class FeedTheFishMod implements ModInitializer {

	// Visible name for the fish food item
	private static final String FISH_FOOD_NAME = "§eFish Food";

	// Run fish behavior every 5 ticks (~4 times per second) – big performance win
	private static final int TICK_INTERVAL = 5;

	// Max fish that will chase one piece of food (prevents lag in big schools)
	private static final int MAX_FISH_PER_FOOD = 6;

	// Max distance squared for fish to notice food (~7 blocks)
	private static final double MAX_NOTICE_DISTANCE_SQ = 49.0;

	// Distance squared when fish eats the food (~0.6 blocks)
	private static final double EAT_DISTANCE_SQ = 0.36;

	@Override
	public void onInitialize() {

		// === RIGHT-CLICK WITH BREAD: Spawn fish food on water ===
		UseItemCallback.EVENT.register((player, world, hand) -> {
			// Only run on server and if holding bread
			if (world.isClient || player.getStackInHand(hand).getItem() != Items.BREAD) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}

			// Raycast to see what the player is aiming at (fluid-aware = sees water)
			HitResult hit = player.raycast(5.5, 0.0f, true);

			// Must hit a block
			if (hit.getType() != HitResult.Type.BLOCK) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}

			BlockHitResult blockHit = (BlockHitResult) hit;
			BlockPos pos = blockHit.getBlockPos();

			// Check if the block is water
			var fluid = world.getFluidState(pos);
			if (!fluid.isIn(FluidTags.WATER) || fluid.getLevel() == 0) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}

			// Spawn position exactly on water surface with small random spread
			double spawnX = pos.getX() + 0.5 + (Math.random() - 0.5) * 0.6;
			double spawnY = pos.getY() + fluid.getHeight(world, pos) + 0.04;
			double spawnZ = pos.getZ() + 0.5 + (Math.random() - 0.5) * 0.6;

			// Create the fish food item
			ItemEntity food = new ItemEntity(world, spawnX, spawnY, spawnZ,
					player.getStackInHand(hand).copyWithCount(1));

			// Small throw effect
			Vec3d look = player.getRotationVec(1.0f);
			food.setVelocity(look.x * 0.18, 0.12, look.z * 0.18);

			// Set name and properties
			food.setCustomName(Text.literal(FISH_FOOD_NAME));
			food.setCustomNameVisible(true);
			food.setPickupDelay(25);
			food.age = 6000 - 900; // ~45 seconds lifetime

			world.spawnEntity(food);

			player.playSound(SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, 0.85f, 1.05f);
			player.getStackInHand(hand).decrement(1);

			return TypedActionResult.success(player.getStackInHand(hand));
		});

		// === SERVER TICK: Make fish chase and eat food (optimized) ===
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			// Only run on server
			if (world.isClient)
				return;

			// Run only every few ticks – huge performance improvement
			if (world.getServer().getTicks() % TICK_INTERVAL != 0)
				return;

			// Safe large box covering the whole loaded world (based on world border)
			double cx = world.getWorldBorder().getCenterX();
			double cz = world.getWorldBorder().getCenterZ();
			double size = world.getWorldBorder().getSize();
			Box worldBox = new Box(cx - size / 2, world.getBottomY(), cz - size / 2, cx + size / 2,
					world.getBottomY() + world.getHeight(), cz + size / 2);

			// Find all fish food items
			for (ItemEntity food : world.getEntitiesByClass(ItemEntity.class, worldBox,
					(ItemEntity e) -> e.getStack().isOf(Items.BREAD) && e.hasCustomName()
							&& FISH_FOOD_NAME.equals(e.getCustomName().getString()))) {

				// Area around the food to look for fish
				Box fishArea = food.getBoundingBox().expand(8.0);
				int fishProcessed = 0;

				// Look at nearby fish
				for (FishEntity fish : world.getEntitiesByClass(FishEntity.class, fishArea,
						(FishEntity f) -> f.isAlive() && f.isSubmergedInWater())) {

					// Stop processing more fish than needed
					if (fishProcessed >= MAX_FISH_PER_FOOD)
						break;

					double distanceSq = fish.squaredDistanceTo(food);

					// Ignore very far fish
					if (distanceSq > MAX_NOTICE_DISTANCE_SQ)
						continue;

					// Make fish swim towards the food
					fish.getNavigation().startMovingTo(food, 1.0);

					// Eat when very close
					if (distanceSq < EAT_DISTANCE_SQ) {
						fish.heal(1.0f);

						// Happy particles
						((ServerWorld) world).spawnParticles(
								net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, food.getX(),
								food.getY() + 0.5, food.getZ(), 8, 0.2, 0.2, 0.2, 0.02);

						// Eating sound
						world.playSound(null, food.getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT,
								net.minecraft.sound.SoundCategory.NEUTRAL, 0.5f, 1.2f);

						food.discard(); // Remove food
						break;
					}

					fishProcessed++;
				}
			}
		});
	}
}
