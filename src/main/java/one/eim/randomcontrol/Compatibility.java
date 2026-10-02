package one.eim.randomcontrol;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static one.eim.randomcontrol.Utils.clazz;
import static one.eim.randomcontrol.Utils.sneakyThrows;
import static one.eim.randomcontrol.Utils.requireNonNullElseGet;

public final class Compatibility {
    private static final boolean hasRandomSource;
    private static final MethodHandle createRandom;
    private static final MethodHandle randomSetSeed;
    private static final Object sharedRandom;
    private static final MethodHandle craftEntityGetHandle;
    private static final MethodHandle entityGetRandom;
    private static final ObjectUnsafe randomFieldWriter;

    static {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();

        final Class<?> entityClass = requireNonNullElseGet(
                clazz("net.minecraft.world.entity.Entity"),
                () -> sneakyThrows(() -> Class.forName(Bukkit.getServer().getClass().getName()
                        .replace("org.bukkit.craftbukkit", "net.minecraft.server")
                        .replace("CraftServer", "Entity")))
        );

        final @Nullable Class<?> randomSourceClass = clazz("net.minecraft.util.RandomSource");
        hasRandomSource = randomSourceClass != null;

        createRandom = hasRandomSource
                ? sneakyThrows(() -> lookup.unreflect(
                Arrays.stream(randomSourceClass.getDeclaredMethods())
                        .filter(m -> m.getName().equals("create")
                                && m.getReturnType() == randomSourceClass
                                && m.getParameterCount() == 0
                                && Modifier.isStatic(m.getModifiers())
                                && Modifier.isPublic(m.getModifiers()))
                        .findFirst()
                        .orElseThrow(() -> new ExceptionInInitializerError(
                                "Failed to locate RandomSource.create() method"))
        ))
                : sneakyThrows(() -> lookup.findConstructor(Random.class,
                java.lang.invoke.MethodType.methodType(void.class)));

        randomSetSeed = hasRandomSource
                ? sneakyThrows(() -> lookup.unreflect(
                Arrays.stream(randomSourceClass.getDeclaredMethods())
                        .filter(m -> m.getName().equals("setSeed")
                                && m.getReturnType() == void.class
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == long.class
                                && Modifier.isPublic(m.getModifiers()))
                        .findFirst()
                        .orElseThrow(() -> new ExceptionInInitializerError(
                                "Failed to locate RandomSource#setSeed(long) method"))
        ))
                : sneakyThrows(() -> lookup.unreflect(Random.class.getMethod("setSeed", long.class)));

        final Field randomField;
        try {
            randomField = entityClass.getDeclaredField("random");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(
                    "Failed to find Entity.random. The server may not be Paper or its Entity implementation changed.");
        }

        randomField.setAccessible(true);

        sharedRandom = sneakyThrows(() -> entityClass.getField("SHARED_RANDOM").get(null));

        final Class<?> craftEntityClass = Objects.requireNonNull(
                clazz(Bukkit.getServer().getClass().getName().replace("CraftServer", "entity.CraftEntity")),
                "Can not find CraftEntity - not Paper?"
        );

        craftEntityGetHandle = sneakyThrows(() ->
                lookup.unreflect(craftEntityClass.getDeclaredMethod("getHandle")));

        entityGetRandom = sneakyThrows(() -> lookup.unreflectGetter(randomField));

        /*
         * Paper 26.2 declares Entity.random as:
         *
         *     protected final RandomSource random = SHARED_RANDOM;
         *
         * A normal MethodHandle setter is therefore not usable on Java 25.
         * Use Unsafe only for the final-field replacement that RandomControl
         * specifically needs.
         */
        randomFieldWriter = ObjectUnsafe.create(randomField);
    }

    public static void updateRandom(final org.bukkit.entity.Entity entity, final @Nullable Long seed) {
        try {
            final Object wrapped = craftEntityGetHandle.invoke(entity);

            if (entityGetRandom.invoke(wrapped) != sharedRandom) {
                return;
            }

            final Object random = createRandom.invoke();
            if (seed != null) {
                randomSetSeed.invoke(random, seed);
            }

            randomFieldWriter.set(wrapped, random);
        } catch (final Throwable t) {
            throw new RuntimeException(
                    "Failed to update random on entity " + entity.getName() + " "
                            + entity.getLocation() + " " + entity.getUniqueId(), t);
        }
    }

    private static final class ObjectUnsafe {
        private static final sun.misc.Unsafe UNSAFE = getUnsafe();
        private final long offset;

        private ObjectUnsafe(final long offset) {
            this.offset = offset;
        }

        static ObjectUnsafe create(final Field field) {
            if (UNSAFE == null) {
                throw new ExceptionInInitializerError("sun.misc.Unsafe is unavailable; cannot replace Paper's final Entity.random field.");
            }
            return new ObjectUnsafe(UNSAFE.objectFieldOffset(field));
        }

        void set(final Object target, final Object value) {
            UNSAFE.putObject(target, offset, value);
        }

        private static sun.misc.Unsafe getUnsafe() {
            try {
                final Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                return (sun.misc.Unsafe) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}
