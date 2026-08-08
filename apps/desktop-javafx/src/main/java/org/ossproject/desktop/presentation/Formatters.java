package org.ossproject.desktop.presentation;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
public final class Formatters {
    private static final NumberFormat KRW = NumberFormat.getIntegerInstance(Locale.KOREA);
    private Formatters() {}
    public static String won(BigDecimal value) { return KRW.format(value) + "원"; }
}
