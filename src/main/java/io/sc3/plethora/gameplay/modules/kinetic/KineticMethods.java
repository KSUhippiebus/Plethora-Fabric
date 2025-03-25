package io.sc3.plethora.gameplay.modules.kinetic;

import com.mojang.authlib.GameProfile;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import io.sc3.plethora.api.IPlayerOwnable;
import io.sc3.plethora.api.method.FutureMethodResult;
import io.sc3.plethora.api.method.IContext;
import io.sc3.plethora.api.method.IUnbakedContext;
import io.sc3.plethora.api.module.IModuleContainer;
import io.sc3.plethora.api.module.SubtargetedModuleMethod;
import io.sc3.plethora.gameplay.PlethoraFakePlayer;
import io.sc3.plethora.mixin.ServerPlayNetworkHandlerAdapter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


//import com.mojang.brigadier.Command;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;


import static io.sc3.plethora.Plethora.config;
import static io.sc3.plethora.api.method.ArgumentExt.assertDoubleBetween;
import static io.sc3.plethora.api.method.ContextKeys.ORIGIN;
import static io.sc3.plethora.core.ContextHelpers.fromContext;
import static io.sc3.plethora.core.ContextHelpers.fromSubtarget;
import static io.sc3.plethora.gameplay.registry.PlethoraModules.KINETIC_M;
import static io.sc3.plethora.util.Helpers.normaliseAngle;

public class KineticMethods {
  private static final double TERMINAL_VELOCITY = -2;

  public static final SubtargetedModuleMethod<LivingEntity> LAUNCH = SubtargetedModuleMethod.of(
    "launch", KINETIC_M, LivingEntity.class,
    "function(yaw:number, pitch:number, power:number) -- Launch the entity in a set direction",
    KineticMethods::launch
  );
  private static FutureMethodResult launch(@Nonnull IUnbakedContext<IModuleContainer> unbaked,
                                           @Nonnull IArguments args) throws LuaException {
    final float yaw = (float) normaliseAngle(args.getFiniteDouble(0));
    final float pitch = (float) normaliseAngle(args.getFiniteDouble(1));
    final float power = (float) assertDoubleBetween(args, 2, 0, config.kinetic.launchMax, "Power out of range (%s).");

    return unbaked.getCostHandler().await(power * config.kinetic.launchCost, FutureMethodResult.nextTick(() -> {
      LivingEntity entity = unbaked.bake().getContext(ORIGIN, LivingEntity.class);
      launch(entity, yaw, pitch, power);
      return FutureMethodResult.empty();
    }));
  }

  public static void launch(Entity entity, float yaw, float pitch, float power) {
    float motionX = -MathHelper.sin(yaw / 180.0f * (float) Math.PI) * MathHelper.cos(pitch / 180.0f * (float) Math.PI);
    float motionZ = MathHelper.cos(yaw / 180.0f * (float) Math.PI) * MathHelper.cos(pitch / 180.0f * (float) Math.PI);
    float motionY = -MathHelper.sin(pitch / 180.0f * (float) Math.PI);

    power /= MathHelper.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
    if (entity instanceof LivingEntity living && living.isFallFlying()) {
      power *= config.kinetic.launchElytraScale;
    }

    entity.addVelocity(motionX * power, motionY * power * config.kinetic.launchYScale, motionZ * power);
    entity.velocityModified = true; // Equivalent to velocityChanged, sends an EntityVelocityUpdateS2CPacket

    if (config.kinetic.launchFallReset && motionY > 0) {
      double entityVelY = entity.getVelocity().y;
      if (entityVelY > 0) {
        entity.fallDistance = 0;
      } else if (entityVelY > TERMINAL_VELOCITY) {
        entity.fallDistance *= entityVelY / TERMINAL_VELOCITY;
      }
    }

    if (config.kinetic.launchFloatReset && entity instanceof ServerPlayerEntity spe) {
      ((ServerPlayNetworkHandlerAdapter) spe.networkHandler).setFloatingTicks(0);
    }
  }

  // New function to run the walk command
  public static final SubtargetedModuleMethod<LivingEntity> WALK = SubtargetedModuleMethod.of(
    "walk", KINETIC_M, LivingEntity.class,
    "function(direction:string) -- Make the player walk in a specified direction ('forward', 'backward', 'left', 'right')",
    KineticMethods::walk
  );

  private static FutureMethodResult walk(@Nonnull IUnbakedContext<IModuleContainer> unbaked, @Nonnull IArguments args) throws LuaException {
    // Extract the direction argument
    String direction = args.getString(0);
    if (!direction.equals("forward") && !direction.equals("backward") && !direction.equals("left") && !direction.equals("right")) {
      throw new LuaException("Invalid argument. Expected 'forward', 'backward', 'left', or 'right'.");
    }

    return unbaked.getCostHandler().await(0, FutureMethodResult.nextTick(() -> {
      KineticMethodContext context = getContext(unbaked);  // Get the context
      KineticMethodPlayer playerData = getPlayer(context);  // Get player info

      // Get the player's username
      String username = playerData.player.getGameProfile().getName();

      // Run the command
      runCommand(playerData.world, username, direction);
      return FutureMethodResult.empty();
    }));
  }

  // Helper function to execute the /player [USERNAME] walk [ARG] command
  private static void runCommand(ServerWorld world, String username, String direction) throws LuaException {
    // Build the command string
    String command = String.format("player %s walk %s", username, direction);

    // Get the player's command source
    ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(username);
    if (player == null) {
      throw new LuaException("Player not found");
    }

    // Get the server's command source
    ServerCommandSource source = world.getServer().getCommandSource();

    // Get the command manager
    CommandManager commandManager = world.getServer().getCommandManager();

    // Parse the command
    try {
      CommandDispatcher<ServerCommandSource> dispatcher = commandManager.getDispatcher();
      dispatcher.execute(command, source);
    } catch (CommandSyntaxException e) {
      // Handle errors when parsing or executing the command
      e.printStackTrace();
    }
  }

  public record KineticMethodContext(IContext<IModuleContainer> context, LivingEntity entity,
                                     @Nullable IPlayerOwnable ownable) {}
  public static KineticMethodContext getContext(@Nonnull IUnbakedContext<IModuleContainer> unbaked) throws LuaException {
    IContext<IModuleContainer> ctx = unbaked.bake();
    LivingEntity entity = fromSubtarget(ctx, LivingEntity.class);
    IPlayerOwnable ownable = fromContext(ctx, IPlayerOwnable.class, ORIGIN);
    return new KineticMethodContext(ctx, entity, ownable);
  }

  public record KineticMethodPlayer(ServerWorld world, ServerPlayerEntity player, PlethoraFakePlayer fakePlayer) {}
  public static KineticMethodPlayer getPlayer(@Nonnull KineticMethodContext ctx) throws LuaException {
    LivingEntity entity = ctx.entity();
    IPlayerOwnable ownable = ctx.ownable();

    if (!(entity.getEntityWorld() instanceof ServerWorld world)) {
      throw new LuaException("Cannot run on client");
    }

    ServerPlayerEntity player;
    PlethoraFakePlayer fakePlayer;
    if (entity instanceof ServerPlayerEntity spe) {
      player = spe;
      fakePlayer = null;
    } else if (entity instanceof PlayerEntity) {
      throw new LuaException("An unexpected player was used");
    } else {
      if (ownable == null) throw new LuaException("Could not determine owner");
      GameProfile profile = ownable.getOwningProfile();
      if (profile == null) throw new LuaException("Could not determine owner profile");
      player = fakePlayer = new PlethoraFakePlayer(world, entity, ownable.getOwningProfile());
    }

    return new KineticMethodPlayer(world, player, fakePlayer);
  }
}
