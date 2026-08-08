package org.ossproject.accessibility.notification;

public enum SpeechPriority {
    CRITICAL(600), ORDER(500), CONNECTION(400), ALERT(300), USER_REQUEST(200), INFORMATION(100);
    private final int weight;
    SpeechPriority(int weight) { this.weight = weight; }
    public int weight() { return weight; }
}
