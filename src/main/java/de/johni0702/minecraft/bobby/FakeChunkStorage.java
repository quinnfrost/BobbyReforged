package de.johni0702.minecraft.bobby;

import com.mojang.serialization.Codec;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.storage.StorageIoWorker;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeChunkStorage extends VersionedChunkStorage {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<Path, FakeChunkStorage> active = new HashMap<>();

    public static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    public static FakeChunkStorage getFor(Path directory, boolean writeable) {
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw new IllegalStateException("Must be called from main thread.");
        }
        return active.computeIfAbsent(directory, f -> new FakeChunkStorage(directory, writeable));
    }

    public static void closeAll() {
        for (FakeChunkStorage storage : active.values()) {
            try {
                storage.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close storage", e);
            }
        }
        active.clear();
    }

    private final Path directory;
    private final boolean writeable;
    private final AtomicBoolean sentUpgradeNotification = new AtomicBoolean();
    @Nullable
    private final LastAccessFile lastAccess;

    private FakeChunkStorage(Path directory, boolean writeable) {
        super(directory, MinecraftClient.getInstance().getDataFixer(), false);

        this.directory = directory;
        this.writeable = writeable;

        LastAccessFile lastAccess = null;
        if (writeable) {
            try {
                Files.createDirectories(directory);

                lastAccess = new LastAccessFile(directory);
            } catch (IOException e) {
                LOGGER.error("Failed to read last_access file:", e);
            }
        }
        this.lastAccess = lastAccess;
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (lastAccess != null) {
            int deleteUnusedRegionsAfterDays = BobbyConfig.getDeleteUnusedRegionsAfterDays();
            if (deleteUnusedRegionsAfterDays >= 0) {
                for (long entry : lastAccess.pollRegionsOlderThan(deleteUnusedRegionsAfterDays)) {
                    int x = ChunkPos.getPackedX(entry);
                    int z = ChunkPos.getPackedZ(entry);
                    Files.deleteIfExists(directory.resolve("r." + x + "." + z + ".mca"));
                }
            }

            lastAccess.close();
        }
    }

    public void save(ChunkPos pos, NbtCompound chunk) {
        if (lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        setNbt(pos, chunk);
    }

    public CompletableFuture<Optional<NbtCompound>> loadTag(ChunkPos pos) {
        try {
            return CompletableFuture.completedFuture(Optional.of(getNbt(pos))).thenApply(maybeNbt -> maybeNbt.map(nbt -> loadTag(pos, nbt)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private NbtCompound loadTag(ChunkPos pos, NbtCompound nbt) {
        if (nbt != null && lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        if (nbt != null && nbt.getInt("DataVersion") != SharedConstants.getGameVersion().getSaveVersion().getId()) {
            if (sentUpgradeNotification.compareAndSet(false, true)) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.submit(() -> {
                    Text text = Text.of(writeable ? "bobby.upgrade.required" : "bobby.upgrade.fallback_world");
                    client.submit(() -> client.inGameHud.getChatHud().addMessage(text));
                });
            }
            return null;
        }
        return nbt;
    }

    public void upgrade(RegistryKey<World> worldKey, BiConsumer<Integer, Integer> progress) throws IOException {
        Optional<RegistryKey<Codec<? extends ChunkGenerator>>> generatorKey =
                Optional.of(Registry.CHUNK_GENERATOR.getKey(FlatChunkGenerator.CODEC).orElseThrow());

        List<ChunkPos> chunks;
        try (Stream<Path> stream = Files.list(directory)) {
            chunks = stream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(REGION_FILE_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .map(it -> new RegionPos(Integer.parseInt(it.group(1)), Integer.parseInt(it.group(2))))
                    .flatMap(RegionPos::getContainedChunks)
                    .collect(Collectors.toList());
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger total = new AtomicInteger(chunks.size());
        progress.accept(done.get(), total.get());

        StorageIoWorker io = (StorageIoWorker) getWorker();

        // We ideally split the actual work of upgrading the chunk NBT across multiple threads, leaving a few for MC
        int workThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService workExecutor = Executors.newFixedThreadPool(workThreads, new DefaultThreadFactory("bobby-upgrade-worker", true));

        try {
            for (ChunkPos chunkPos : chunks) {
                workExecutor.submit(() -> {
                    NbtCompound nbt;
                    try {
                        nbt = CompletableFuture.completedFuture(io.getNbt(chunkPos)).join();
                    } catch (CompletionException | IOException e) {
                        LOGGER.warn("Error reading chunk " + chunkPos.x + "/" + chunkPos.z + ":", e);
                        nbt = null;
                    }

                    if (nbt == null) {
                        progress.accept(done.get(), total.decrementAndGet());
                        return;
                    }

                    // Didn't have this set prior to Bobby 4.0.5 and upgrading from 1.18 to 1.19 wipes light data
                    // from chunks that don't have this set, so we need to set it before we upgrade the chunk.
                    nbt.putBoolean("isLightOn", true);

                    nbt = updateChunkNbt(worldKey, null, nbt, generatorKey);

                    io.setResult(chunkPos, nbt).join();

                    progress.accept(done.incrementAndGet(), total.get());
                });
            }
        } finally {
            workExecutor.shutdown();
        }

        try {
            //noinspection ResultOfMethodCallIgnored
            workExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        progress.accept(done.get(), total.get());
    }

    private static final class RegionPos
    {
        private final int x;
        private final int z;

        private RegionPos(int x, int z)
        {
            this.x = x;
            this.z = z;
        }

        public Stream<ChunkPos> getContainedChunks()
        {
            int baseX = x << 5;
            int baseZ = z << 5;
            ChunkPos[] result = new ChunkPos[32 * 32];
            for (int x = 0; x < 32; x++)
            {
                for (int z = 0; z < 32; z++)
                {
                    result[x * 32 + z] = new ChunkPos(baseX + x, baseZ + z);
                }
            }
            return Stream.of(result);
        }

        public int x()
        {
            return x;
        }

        public int z()
        {
            return z;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (RegionPos) obj;
            return this.x == that.x &&
                    this.z == that.z;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(x, z);
        }

        @Override
        public String toString()
        {
            return "RegionPos[" +
                    "x=" + x + ", " +
                    "z=" + z + ']';
        }

    }
}
