package org.researchzosho.librarian.dna;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The arithmetic of the measurement tooling, reproduced: the DNA's numbers were first computed by Python scripts and the Java port is
 * tested against their output, so a mean is the correctly rounded sum divided by the count, a standard deviation is taken from the
 * exact sum of squares, and rounding is of the exact binary value with ties to even — never the shortcuts that differ in the last digit.
 */
final class Py {
    private Py() { }

    static final MathContext MC = new MathContext(60);

    /** {@code round(v, places)}. */
    static double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return new BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** The exact sum of the values, as the nearest double ({@code math.fsum}). */
    static double fsum(Collection<? extends Number> xs) {
        BigDecimal s = BigDecimal.ZERO;
        for (Number x : xs) s = s.add(exact(x));
        return s.doubleValue();
    }

    static double fsum(double[] xs) { BigDecimal s = BigDecimal.ZERO; for (double x : xs) s = s.add(new BigDecimal(x)); return s.doubleValue(); }

    /** {@code statistics.fmean}: the exact sum, then one division. */
    static double fmean(Collection<? extends Number> xs) { return fsum(xs) / xs.size(); }

    static double fmean(double[] xs) { return fsum(xs) / xs.length; }

    /** {@code statistics.pstdev}: the population standard deviation from the exact sum of squares. */
    static double pstdev(Collection<? extends Number> xs) {
        int n = xs.size();
        if (n == 0) return 0;
        BigDecimal s = BigDecimal.ZERO, sq = BigDecimal.ZERO;
        for (Number x : xs) { BigDecimal b = exact(x); s = s.add(b); sq = sq.add(b.multiply(b)); }
        BigDecimal bn = BigDecimal.valueOf(n);
        BigDecimal var = bn.multiply(sq).subtract(s.multiply(s)).divide(bn.multiply(bn), MC);
        if (var.signum() <= 0) return 0;
        return var.sqrt(MC).doubleValue();
    }

    static double pstdev(double[] xs) { List<Double> l = new ArrayList<>(xs.length); for (double x : xs) l.add(x); return pstdev(l); }

    /** {@code statistics.median}: the middle value, or the mean of the two middle values. */
    static double median(List<Integer> xs) {
        List<Integer> s = new ArrayList<>(xs); s.sort(null);
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static BigDecimal exact(Number x) {
        if (x instanceof Integer || x instanceof Long) return BigDecimal.valueOf(x.longValue());
        return new BigDecimal(x.doubleValue());
    }
}
