package ru.cherdakmd.chrdkregister.api;

import java.math.BigInteger;

/** API 29: точный остаток выручки меньше алмаза; не переводится и не тратится отдельно от /bal. */
public final class ShopSaleRemainder {
    public static final String ZERO = "0/1";
    private record Fraction(BigInteger n, BigInteger d) {
        Fraction { if(n.signum()<0 || d.signum()<=0) throw new IllegalArgumentException("sale fraction"); var g=n.gcd(d); n=n.divide(g); d=d.divide(g); }
        String text() { return n+"/"+d; }
    }
    private static Fraction parse(String text) {
        if(text==null || text.length()>8193 || !text.matches("[0-9]{1,4096}/[1-9][0-9]{0,4095}")) throw new IllegalArgumentException("sale remainder");
        var parts=text.split("/"); var f=new Fraction(new BigInteger(parts[0]),new BigInteger(parts[1]));
        if(f.n.compareTo(f.d)>=0 || !f.text().equals(text)) throw new IllegalArgumentException("noncanonical remainder");
        return f;
    }
    public static boolean valid(String text) { try { parse(text); return true; } catch(RuntimeException error) { return false; } }
    public record Quote(long money, String remainder) { }
    public static Quote quote(String before,int batch,long price,long units) {
        var f=parse(before);
        if(batch<1 || batch>2304 || price<1 || price>1_000_000_000L || units<1 || units>2304) throw new IllegalArgumentException("sale quote");
        var d=f.d.multiply(BigInteger.valueOf(batch));
        var n=f.n.multiply(BigInteger.valueOf(batch)).add(f.d.multiply(BigInteger.valueOf(price)).multiply(BigInteger.valueOf(units)));
        var parts=n.divideAndRemainder(d);
        String remainder=new Fraction(parts[1],d).text();
        if(!valid(remainder)) throw new IllegalArgumentException("sale remainder overflow");
        return new Quote(parts[0].longValueExact(),remainder);
    }
    private ShopSaleRemainder() { }
}
