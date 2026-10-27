package io.github.tortaje.fitnesse.health;

import fitnesse.plugins.PluginFeatureFactory;
import fitnesse.responders.ResponderFactory;

public class HealthPluginFeatureFactory implements PluginFeatureFactory {

    @Override
    public void registerResponders(ResponderFactory responderFactory) {
        responderFactory.addResponder("health-live", LivenessResponder.class);
        responderFactory.addResponder("health-ready", ReadinessResponder.class);
    }
}
