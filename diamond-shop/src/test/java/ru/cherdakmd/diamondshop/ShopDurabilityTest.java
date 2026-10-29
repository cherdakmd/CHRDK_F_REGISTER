package ru.cherdakmd.diamondshop;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Реальный NIO writer; test seam находится сразу после atomic rename. Не native crash-test. */
class ShopDurabilityTest {
    @TempDir Path dir;
    final UUID id=UUID.randomUUID();
    final Logger log=Logger.getAnonymousLogger();
    final ShopConfig.ShopItem item=new ShopConfig.ShopItem("STONE","blocks",1,1000,.25,4);
    Path file() { return dir.resolve("shop-data.json"); }
    ShopStore load() { var s=new ShopStore(dir,log); s.load(); return s; }
    void seed(String amount) throws Exception { Files.writeString(file(),"{\"balances\":{\""+id+"\":"+amount+"}}"); }
    ShopStore funded() throws Exception { seed("12.350000000000000000001"); var s=load(); s.setStock(item,500,1000); s.saveNow(); return s; }
    @Test void previewDoesNotAcknowledgeOrLoseUnsavedChanges() throws Exception {
        var s=funded(); s.setStock(item,600,1000); assertTrue(s.isDirty()); s.snapshotJson(); assertTrue(s.isDirty()); assertEquals(500,load().snapshotOf("STONE").stock);
        s.saveNow(); assertFalse(s.isDirty()); assertEquals(600,load().snapshotOf("STONE").stock);
    }
    @Test void exactLegacyCeilingDoesNotRoundDownNearAnInteger() throws Exception {
        seed("1.00000000000000000000000001"); var s=load(); assertEquals(1d,s.balanceOf(id)); assertEquals(2,s.legacyAmount(id));
        s.setStock(item,1,1000); s.saveNow(); assertEquals(2,load().legacyAmount(id));
        assertEquals(new BigDecimal("1.00000000000000000000000001"),JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonObject("balances").get(id.toString()).getAsBigDecimal());
    }
    @Test void tinyPositiveLegacyAmountStillImportsOneRatherThanZero() throws Exception {
        seed("1e-1000"); var s=load(); assertTrue(s.legacyAvailable()); assertEquals(0d,s.balanceOf(id)); assertEquals(1,s.legacyAmount(id));
    }
    @Test void fractionalTailAtCapCannotBeAccidentallyRoundedIntoAllowedRange() throws Exception {
        seed("1000000000.000000000000000001"); var s=load(); assertEquals(-1,s.legacyAmount(id)); assertTrue(s.legacyAvailable()); s.setStock(item,1,1000); s.saveNow(); assertEquals(-1,load().legacyAmount(id));
    }
    @Test void zeroAndExactIntegerAndMissingPlayerHaveNoInventedFraction() throws Exception {
        for(String amount:List.of("0","1","43","1000000000")) { seed(amount); var s=load(); assertEquals(Long.parseLong(amount),s.legacyAmount(id)); assertEquals(0,s.legacyAmount(UUID.randomUUID())); }
    }
    @Test void hugeObligationSurvivesSaveEvenWhenNotRepresentableAsDouble() throws Exception {
        seed("1e1000"); var s=load(); assertTrue(s.legacyAvailable()); assertEquals(-1,s.legacyAmount(id)); s.setStock(item,500,1000); s.saveNow();
        assertEquals(new BigDecimal("1e1000"),JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonObject("balances").get(id.toString()).getAsBigDecimal()); assertEquals(-1,load().legacyAmount(id));
    }
    @Test void inspectionCopiesCannotMutateTheLegacySource() throws Exception {
        seed("12.35"); var s=load(); var view=s.legacyBalances(); assertThrows(UnsupportedOperationException.class,()->view.put(id,999d)); assertEquals(13,s.legacyAmount(id));
    }
    @Test void preRenameFailurePreservesOriginalAndDisablesAllFurtherWrites() throws Exception {
        var s=funded(); String original=Files.readString(file()); Files.createDirectory(dir.resolve("shop-data.json.tmp")); s.setStock(item,600,1000); s.saveNow();
        assertFalse(s.legacyAvailable()); assertTrue(s.isDirty()); assertEquals(-1,s.legacyAmount(id)); assertFalse(s.canRecordTurnover(id,1,0,1000)); assertFalse(s.tryRecordTurnover(id,1,0,1000));
        Files.delete(dir.resolve("shop-data.json.tmp")); s.saveNow(); assertEquals(original,Files.readString(file())); assertEquals(500,load().snapshotOf("STONE").stock);
    }
    @Test void postRenameIoFailureLeavesNewFileButNoAcknowledgementOrShutdownRewrite() throws Exception {
        funded(); var s=new ShopStore(dir,log,()->{throw new IOException("after rename");}); s.load(); s.setStock(item,600,1000); s.saveNow();
        assertFalse(s.legacyAvailable()); assertTrue(s.isDirty()); assertEquals(600,load().snapshotOf("STONE").stock); String durable=Files.readString(file()); s.setStock(item,700,1000); s.saveNow(); assertEquals(durable,Files.readString(file()));
    }
    @Test void runtimeAmbiguityAfterRenameAlsoStopsTheWriter() throws Exception {
        funded(); var s=new ShopStore(dir,log,()->{throw new IllegalStateException("runtime after rename");}); s.load(); s.setStock(item,600,1000); s.saveNow();
        assertFalse(s.legacyAvailable()); assertTrue(s.isDirty()); assertEquals(600,load().snapshotOf("STONE").stock); s.saveAsync(); assertEquals(600,load().snapshotOf("STONE").stock);
    }
    @Test void loadDoesNotReopenAQuarantinedInstance() throws Exception {
        var s=funded(); Files.createDirectory(dir.resolve("shop-data.json.tmp")); s.setStock(item,600,1000); s.saveNow(); Files.delete(dir.resolve("shop-data.json.tmp")); s.load(); assertFalse(s.legacyAvailable()); assertEquals(-1,s.legacyAmount(id));
    }
    @Test void freshFileAndCleanLegacySourceCanBeSavedWithoutAnInventedWallet() throws Exception {
        var s=load(); s.saveNow(); assertTrue(Files.isRegularFile(file())); assertTrue(load().legacyBalances().isEmpty()); assertFalse(s.isDirty());
        seed("1.01"); s=load(); String before=Files.readString(file()); s.saveNow(); assertEquals(before,Files.readString(file()));
    }
    @Test void sourceStockLimitAndHistoryRoundTripTogether() throws Exception {
        var s=funded(); long time=86400000L*21500+1; s.adjustStock(item,-2,time,0); s.recordTrade(item,2,false,4); assertTrue(s.tryRecordTurnover(id,4,100,time)); s.recordPrice(item,2,time,24,1000); s.saveNow();
        var saved=load(); assertTrue(saved.legacyAvailable()); assertEquals(498,saved.snapshotOf("STONE").stock); assertEquals(2,saved.snapshotOf("STONE").boughtByPlayers); assertEquals(4,saved.turnover()); assertEquals(4,saved.usedToday(id,time)); assertArrayEquals(new double[]{2},saved.historyOf("STONE")); assertEquals(13,saved.legacyAmount(id));
    }
    @Test void asyncWriterAndMarketMutationShareTheSameMonitor() throws Exception {
        funded(); var ref=new AtomicReference<ShopStore>(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1); var held=new AtomicBoolean(); var errors=new AtomicReference<Throwable>();
        var s=new ShopStore(dir,log,()->{if(Thread.currentThread().getName().equals("old-shop-save")) {
            held.set(Thread.holdsLock(ref.get())); entered.countDown(); try {if(!release.await(5,TimeUnit.SECONDS)) throw new IOException("timeout");} catch(InterruptedException e) {Thread.currentThread().interrupt(); throw new IOException(e);}
        }}); ref.set(s); s.load(); s.setStock(item,600,1000);
        var writer=new Thread(s::saveAsync,"old-shop-save"); var mutation=new Thread(()->{started.countDown(); try {s.setStock(item,700,1000);} catch(Throwable e) {errors.set(e);}}); writer.start();
        try {assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(held.get()); mutation.start(); assertTrue(started.await(5,TimeUnit.SECONDS));} finally {release.countDown();}
        writer.join(5000); mutation.join(5000); assertFalse(writer.isAlive()); assertFalse(mutation.isAlive()); assertNull(errors.get()); assertTrue(s.isDirty()); assertEquals(600,load().snapshotOf("STONE").stock); s.saveNow(); assertEquals(700,load().snapshotOf("STONE").stock); assertFalse(s.isDirty());
    }
    void refuse(String value) throws Exception {
        Files.writeString(file(),value); var s=load(); assertFalse(s.legacyAvailable(),value); s.saveNow(); assertEquals(value,Files.readString(file()));
    }
    @Test void strictJsonRejectsDuplicatesTrailingDataAndLenientSyntax() throws Exception {
        for(String bad:List.of("{\"balances\":{},\"balances\":{}}","{\"balances\":{}} trailing","{balances:{}}","{\"balances\":{}} {}","/* comment */ {\"balances\":{}}","null","[]","{}")) refuse(bad);
        refuse("{\"balances\":{\""+id+"\":1,\""+id+"\":2}}");
    }
    @Test void invalidLegacyAmountsAndUuidAliasesAreNotSkippedOrCoerced() throws Exception {
        for(String amount:List.of("null","\"12.35\"","-0.01","true","[]","{}","NaN","1e2147483647")) refuse("{\"balances\":{\""+id+"\":"+amount+"}}");
        for(String key:List.of("broken","1-1-1-1-1","AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) refuse("{\"balances\":{\""+key+"\":1}}");
    }
    @Test void wrongTablesAndUnknownFutureFinancialFieldsFailClosed() throws Exception {
        for(String key:List.of("balances","items","limits")) for(String bad:List.of("null","[]","1")) {
            var root=new JsonObject(); root.add("balances",new JsonObject()); root.add(key,JsonParser.parseString(bad)); refuse(root.toString());
        }
        refuse("{\"balances\":{},\"pending\":{}}"); refuse("{\"balances\":{},\"schema\":2}");
    }
    @Test void damagedItemCountersAndHistoryDoNotResetToZero() throws Exception {
        funded(); var root=JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        for(String key:List.of("stock","recoveryAt","soldByPlayers","boughtByPlayers","historyAt")) for(String bad:List.of("-1","\"1\"","1.5","9223372036854775808","null")) {
            var copy=root.deepCopy(); copy.getAsJsonObject("items").getAsJsonObject("STONE").add(key,JsonParser.parseString(bad)); refuse(copy.toString());
        }
        for(String bad:List.of("null","{}","[\"1\"]","[-1]","[1e1000]")) { var copy=root.deepCopy(); copy.getAsJsonObject("items").getAsJsonObject("STONE").add("history",JsonParser.parseString(bad)); refuse(copy.toString()); }
    }
    @Test void damagedUtcRowsCannotDisappearAndReopenQuota() throws Exception {
        var s=funded(); s.tryRecordTurnover(id,4,100,86400000L); s.saveNow(); var root=JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        for(String key:List.of("dayStart","used")) for(String bad:List.of("-1","\"1\"","null","1e1000")) {
            var copy=root.deepCopy(); copy.getAsJsonObject("limits").getAsJsonObject(id.toString()).add(key,JsonParser.parseString(bad)); refuse(copy.toString());
        }
        var copy=root.deepCopy(); copy.getAsJsonObject("limits").getAsJsonObject(id.toString()).addProperty("dayStart",1); refuse(copy.toString());
    }
    @Test void failedLoadDoesNotPartiallyPublishEarlierValidRows() throws Exception {
        seed("12.35"); var s=load(); var root=new JsonObject(); var balances=new JsonObject(); balances.addProperty(UUID.randomUUID().toString(),3); root.add("balances",balances); root.addProperty("items",false); Files.writeString(file(),root.toString()); s.load();
        assertFalse(s.legacyAvailable()); assertEquals(12.35,s.balanceOf(id)); assertEquals(1,s.legacyBalances().size());
    }
    @Test void snapshotStateDoesNotExposeMutableHistory() throws Exception {
        var s=funded(); s.recordPrice(item,2,1000,24,100); var copy=s.snapshotOf("STONE"); copy.history[0]=999; copy.stock=999; assertEquals(500,s.snapshotOf("STONE").stock); assertEquals(2,s.historyOf("STONE")[0]);
    }
    @Test void zeroNegativeAndNonfiniteTurnoverRequestsDoNotGrantQuota() throws Exception {
        var s=funded(); for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {assertFalse(s.canRecordTurnover(id,value,0,1000)); assertFalse(s.tryRecordTurnover(id,value,0,1000));} assertEquals(0,s.usedToday(id,1000));
    }
    @Test void invalidLimitSettingsDoNotDisableTheLimitAccidentally() throws Exception {
        var s=funded(); for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {assertFalse(s.canRecordTurnover(id,1,value,1000)); assertFalse(s.tryRecordTurnover(id,1,value,1000));}
    }
    @Test void directoryInPlaceOfSourceStopsWriterRatherThanCreatingEmptyStore() throws Exception {
        Files.createDirectory(file()); var s=load(); assertFalse(s.legacyAvailable()); s.saveNow(); assertTrue(Files.isDirectory(file()));
    }
    @Test void oversizedInputIsRejectedBeforeParsingAndNeverReplaced() throws Exception {
        try(var channel=java.nio.channels.FileChannel.open(file(),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {channel.position(64L*1024*1024); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));}
        var s=load(); assertFalse(s.legacyAvailable()); s.saveNow(); assertEquals(64L*1024*1024+1,Files.size(file()));
    }
    @Test void excessiveNestingIsRejectedWithoutDroppingTheOriginal() throws Exception {
        refuse("{\"balances\":{},\"items\":"+"[".repeat(70)+"0"+"]".repeat(70)+"}");
    }
    @Test void migrationUsesExactCanonicalSourceRatherThanAnApproximatePreview() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopService.java")); assertTrue(source.contains("store.legacyAmount(id)")); assertFalse(source.contains("WholeTrades.legacy(old)"));
        var commands=Files.readString(Path.of("src/main/java/ru/cherdakmd/diamondshop/ShopCommandHandler.java")); assertTrue(commands.contains("store().legacyAvailable()")); assertTrue(commands.contains("trading.storage-unavailable"));
    }
}
