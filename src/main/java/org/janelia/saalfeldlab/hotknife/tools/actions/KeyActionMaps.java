package org.janelia.saalfeldlab.hotknife.tools.actions;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.util.BdvHandle;

public class KeyActionMaps {

	private final ActionMap ksActionMap;
	private final KeyStrokeAdder ksKeyStrokeAdder;

	public KeyActionMaps(final String context,
						 final BdvHandle handle) {
		this(context, new InputTriggerConfig(), handle.getKeybindings());
	}

	public KeyActionMaps(final String context,
						 final InputTriggerConfig config,
						 final InputActionBindings inputActionBindings) {
		this.ksActionMap = new ActionMap();
		final InputMap ksInputMap = new InputMap();
		this.ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, context);

		inputActionBindings.addActionMap(context, ksActionMap);
		inputActionBindings.addInputMap(context, ksInputMap);
	}

	public void register(final NamedTriggerAction action) {
		action.put(ksActionMap);
		ksKeyStrokeAdder.put(action.name(), action.getDefaultTriggers());
	}

}
