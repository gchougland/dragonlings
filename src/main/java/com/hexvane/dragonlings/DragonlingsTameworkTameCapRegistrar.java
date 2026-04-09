package com.hexvane.dragonlings;

import com.hypixel.hytale.logger.HytaleLogger;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Registers Alec's Tamework custom requirement {@value #REQUIREMENT_ID} and custom effect {@value #EFFECT_RECORD_ID}
 * via reflection so this mod loads when Tamework is absent.
 */
public final class DragonlingsTameworkTameCapRegistrar {
    public static final String REQUIREMENT_ID = "dragonlings_tame_cap";
    /** Runs after a successful tame; increments {@link DragonlingsTameCountStore}. */
    public static final String EFFECT_RECORD_ID = "dragonlings_tame_cap_record";

    private DragonlingsTameworkTameCapRegistrar() {}

    public static void register(
            @Nonnull HytaleLogger logger,
            @Nonnull Supplier<DragonlingsTameCapConfig> configSupplier,
            @Nonnull DragonlingsTameCountStore tameCountStore) {
        try {
            Class<?> tameworkClass = Class.forName("com.alechilles.alecstamework.Tamework");
            Object tamework = tameworkClass.getMethod("getInstance").invoke(null);
            if (tamework == null) {
                logger.atWarning().log("Tamework getInstance() is null; tame cap hooks not registered");
                return;
            }
            Object api = tamework.getClass().getMethod("getApi").invoke(tamework);
            if (api == null) {
                logger.atWarning().log("Tamework API is null; tame cap hooks not registered");
                return;
            }
            Object extApi = api.getClass().getMethod("interactionExtensions").invoke(api);
            if (extApi == null) {
                logger.atWarning().log("Tamework interactionExtensions() is null; tame cap hooks not registered");
                return;
            }
            ClassLoader cl = tameworkClass.getClassLoader();
            Class<?> requirementHandlerClass =
                Class.forName("com.alechilles.alecstamework.api.InteractionRequirementHandler", false, cl);
            Class<?> effectHandlerClass =
                Class.forName("com.alechilles.alecstamework.api.InteractionEffectHandler", false, cl);

            Object requirementProxy =
                Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] {requirementHandlerClass},
                    new TameCapRequirementHandler(configSupplier, tameCountStore));
            Method registerRequirement =
                extApi.getClass().getMethod("registerRequirement", String.class, requirementHandlerClass);
            registerRequirement.invoke(extApi, REQUIREMENT_ID, requirementProxy);

            Object effectProxy =
                Proxy.newProxyInstance(
                    cl, new Class<?>[] {effectHandlerClass}, new TameRecordEffectHandler(tameCountStore));
            Method registerEffect = extApi.getClass().getMethod("registerEffect", String.class, effectHandlerClass);
            registerEffect.invoke(extApi, EFFECT_RECORD_ID, effectProxy);

            logger.atInfo().log(
                "Registered Tamework tame cap requirement '%s' and post-tame effect '%s'",
                REQUIREMENT_ID,
                EFFECT_RECORD_ID);
        } catch (ClassNotFoundException e) {
            logger.atInfo().log(
                "Tamework not present; use persisted counts for /dragonlings give only (no interaction hooks)");
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("Could not register Tamework tame cap hooks");
        }
    }

    private static final class TameCapRequirementHandler implements InvocationHandler {
        private final Supplier<DragonlingsTameCapConfig> configSupplier;
        private final DragonlingsTameCountStore tameCountStore;

        TameCapRequirementHandler(
                Supplier<DragonlingsTameCapConfig> configSupplier, DragonlingsTameCountStore tameCountStore) {
            this.configSupplier = configSupplier;
            this.tameCountStore = tameCountStore;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args == null || args.length < 2) {
                return Boolean.FALSE;
            }
            Object context = args[0];
            if (context == null) {
                return Boolean.FALSE;
            }
            UUID playerUuid = (UUID) context.getClass().getMethod("playerUuid").invoke(context);
            if (playerUuid == null) {
                return Boolean.FALSE;
            }
            Object role = context.getClass().getMethod("role").invoke(context);
            if (role == null) {
                return Boolean.FALSE;
            }
            String roleName = (String) role.getClass().getMethod("getRoleName").invoke(role);
            if (roleName == null || roleName.isEmpty()) {
                return Boolean.FALSE;
            }
            DragonlingsTameCapConfig cfg = this.configSupplier.get();
            if (cfg == null) {
                return Boolean.TRUE;
            }
            int max = cfg.getMaxForRole(roleName);
            int n = this.tameCountStore.getCount(playerUuid, roleName);
            return Boolean.valueOf(n < max);
        }
    }

    private static final class TameRecordEffectHandler implements InvocationHandler {
        private final DragonlingsTameCountStore tameCountStore;

        TameRecordEffectHandler(DragonlingsTameCountStore tameCountStore) {
            this.tameCountStore = tameCountStore;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args == null || args.length < 2) {
                return Boolean.TRUE;
            }
            Object context = args[0];
            if (context == null) {
                return Boolean.TRUE;
            }
            UUID playerUuid = (UUID) context.getClass().getMethod("playerUuid").invoke(context);
            Object role = context.getClass().getMethod("role").invoke(context);
            if (role == null) {
                return Boolean.TRUE;
            }
            String roleName = (String) role.getClass().getMethod("getRoleName").invoke(role);
            if (playerUuid != null && roleName != null && !roleName.isEmpty()) {
                this.tameCountStore.recordTame(playerUuid, roleName);
            }
            return Boolean.TRUE;
        }
    }
}
