/*
 * generated by Xtext 2.15.0
 */
package hu.bme.mit.gamma.action.language;


/**
 * Initialization support for running Xtext languages without Equinox extension registry.
 */
public class ActionLanguageStandaloneSetup extends ActionLanguageStandaloneSetupGenerated {

	public static void doSetup() {
		new ActionLanguageStandaloneSetup().createInjectorAndDoEMFRegistration();
	}
}