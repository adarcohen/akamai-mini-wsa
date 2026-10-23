package com.akamai.miniwsa.domain;

public enum Action {
    DENY(20),
    ALERT(10),
    MONITOR(0);

    private final int scoreBonus;

    Action(int scoreBonus) {
        this.scoreBonus = scoreBonus;
    }

    public int scoreBonus() {
        return scoreBonus;
    }
}
