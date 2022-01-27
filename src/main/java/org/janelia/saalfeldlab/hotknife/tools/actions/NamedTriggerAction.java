package org.janelia.saalfeldlab.hotknife.tools.actions;

import org.scijava.ui.behaviour.util.AbstractNamedAction;

public abstract class NamedTriggerAction
        extends AbstractNamedAction {

    private final String[] defaultTriggers;

    public NamedTriggerAction(final String name,
                              final String... defaultTriggers) {

        super(name);
        this.defaultTriggers = defaultTriggers;
    }

    public String[] getDefaultTriggers() {
        return defaultTriggers;
    }

}

