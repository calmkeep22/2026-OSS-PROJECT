package org.ossproject.desktop.viewmodel;

/** 스캐너 결과 한 건. 숫자값을 별도로 보관해 문자열 파싱 없이 정렬한다. */
public record ScannerItem(String market, String symbol, String name, String price, double changeRate,
                          long volume, long tradingValueMillion, String signal) {}
