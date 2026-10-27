package io.github.tortaje.fitnesse.health.jmx;

public class WrongSignatureCheck implements WrongSignatureCheckMBean {
    @Override public String check() { return "UP"; }
}
