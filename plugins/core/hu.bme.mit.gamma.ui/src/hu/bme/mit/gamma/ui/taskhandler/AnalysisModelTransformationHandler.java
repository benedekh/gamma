/********************************************************************************
 * Copyright (c) 2019 Contributors to the Gamma project
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 ********************************************************************************/
package hu.bme.mit.gamma.ui.taskhandler;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;

import hu.bme.mit.gamma.genmodel.derivedfeatures.GenmodelDerivedFeatures;
import hu.bme.mit.gamma.genmodel.model.AnalysisLanguage;
import hu.bme.mit.gamma.genmodel.model.AnalysisModelTransformation;
import hu.bme.mit.gamma.genmodel.model.ComponentReference;
import hu.bme.mit.gamma.genmodel.model.Coverage;
import hu.bme.mit.gamma.genmodel.model.InteractionCoverage;
import hu.bme.mit.gamma.genmodel.model.ModelReference;
import hu.bme.mit.gamma.genmodel.model.OutEventCoverage;
import hu.bme.mit.gamma.genmodel.model.StateCoverage;
import hu.bme.mit.gamma.genmodel.model.TransitionCoverage;
import hu.bme.mit.gamma.genmodel.model.XSTSReference;
import hu.bme.mit.gamma.statechart.composite.AsynchronousAdapter;
import hu.bme.mit.gamma.statechart.composite.AsynchronousComponentInstance;
import hu.bme.mit.gamma.statechart.composite.ComponentInstanceReference;
import hu.bme.mit.gamma.statechart.composite.SynchronousComponentInstance;
import hu.bme.mit.gamma.statechart.derivedfeatures.StatechartModelDerivedFeatures;
import hu.bme.mit.gamma.statechart.interface_.Component;
import hu.bme.mit.gamma.statechart.interface_.Package;
import hu.bme.mit.gamma.statechart.interface_.TimeSpecification;
import hu.bme.mit.gamma.statechart.util.StatechartUtil;
import hu.bme.mit.gamma.transformation.util.SimpleInstanceHandler;
import hu.bme.mit.gamma.uppaal.composition.transformation.AsynchronousInstanceConstraint;
import hu.bme.mit.gamma.uppaal.composition.transformation.AsynchronousSchedulerTemplateCreator.Scheduler;
import hu.bme.mit.gamma.uppaal.composition.transformation.CompositeToUppaalTransformer;
import hu.bme.mit.gamma.uppaal.composition.transformation.Constraint;
import hu.bme.mit.gamma.uppaal.composition.transformation.ModelModifierForTestGeneration.InteractionRepresentation;
import hu.bme.mit.gamma.uppaal.composition.transformation.OrchestratingConstraint;
import hu.bme.mit.gamma.uppaal.composition.transformation.SchedulingConstraint;
import hu.bme.mit.gamma.uppaal.composition.transformation.TestQueryGenerationHandler;
import hu.bme.mit.gamma.uppaal.composition.transformation.api.util.UppaalModelPreprocessor;
import hu.bme.mit.gamma.uppaal.serializer.UppaalModelSerializer;
import hu.bme.mit.gamma.uppaal.transformation.ModelValidator;
import hu.bme.mit.gamma.uppaal.transformation.traceability.G2UTrace;
import hu.bme.mit.gamma.util.FileUtil;
import hu.bme.mit.gamma.util.GammaEcoreUtil;
import hu.bme.mit.gamma.xsts.model.XSTS;
import hu.bme.mit.gamma.xsts.transformation.GammaToXSTSTransformer;
import hu.bme.mit.gamma.xsts.transformation.serializer.ActionSerializer;
import hu.bme.mit.gamma.xsts.uppaal.transformation.XSTSToUppaalTransformer;
import uppaal.NTA;

public class AnalysisModelTransformationHandler extends TaskHandler {
	
	public AnalysisModelTransformationHandler(IFile file) {
		super(file);
	}
	
	public void execute(AnalysisModelTransformation analysisModelTransformation) throws IOException {
		ModelReference modelReference = analysisModelTransformation.getModel();
		setAnalysisModelTransformation(analysisModelTransformation);
		Set<AnalysisLanguage> languagesSet = new HashSet<AnalysisLanguage>(analysisModelTransformation.getLanguages());
		for (AnalysisLanguage analysisLanguage : languagesSet) {
			AnalysisModelTransformer transformer;
			switch (analysisLanguage) {
				case UPPAAL:
					if (modelReference instanceof ComponentReference) {
						transformer = new Gamma2UppaalTransformer();
					}
					else if (modelReference instanceof XSTSReference) {
						transformer = new XSTS2UppaalTransformer();
					}
					else {
						throw new IllegalArgumentException("Not known model reference: " + modelReference);
					}
					break;
				case THETA:
					transformer = new Gamma2XSTSTransformer();
					break;
				case XSTS_UPPAAL:
					transformer = new Gamma2XSTSUppaalTransformer();
					break;
				default:
					throw new IllegalArgumentException("Currently only UPPAAL and Theta are supported.");
			}
			transformer.execute(analysisModelTransformation);
		}
	}
	
	private void setAnalysisModelTransformation(AnalysisModelTransformation analysisModelTransformation) {
		checkArgument(analysisModelTransformation.getFileName().size() <= 1);
		if (analysisModelTransformation.getFileName().isEmpty()) {
			EObject sourceModel = GenmodelDerivedFeatures.getModel(analysisModelTransformation);
			String fileName = getNameWithoutExtension(getContainingFileName(sourceModel));
			analysisModelTransformation.getFileName().add(fileName);
		}
		checkArgument(analysisModelTransformation.getScheduler().size() <= 1);
		if (analysisModelTransformation.getScheduler().isEmpty()) {
			analysisModelTransformation.getScheduler().add(hu.bme.mit.gamma.genmodel.model.Scheduler.RANDOM);
		}
	}
	
	abstract class AnalysisModelTransformer {
		protected GammaEcoreUtil ecoreUtil = GammaEcoreUtil.INSTANCE;
		protected FileUtil fileUtil = FileUtil.INSTANCE;
		
		protected final String XSTS_EMF_EXTENSION = "gsts";
		protected final String XSTS_XTEXT_EXTENSION = "xsts";
		
		public abstract void execute(AnalysisModelTransformation analysisModelTransformation);
	}
	
	class Gamma2UppaalTransformer extends AnalysisModelTransformer {
		
		public void execute(AnalysisModelTransformation analysisModelTransformation) {
			// Unfolding the given system
			ComponentReference componentReference = (ComponentReference) analysisModelTransformation.getModel();
			Component component = componentReference.getComponent();
			Package gammaPackage = StatechartModelDerivedFeatures.getContainingPackage(component);
			UppaalModelPreprocessor preprocessor = UppaalModelPreprocessor.INSTANCE;
			Component newTopComponent = preprocessor.preprocess(gammaPackage, componentReference.getArguments(),
				new File(targetFolderUri + File.separator + analysisModelTransformation.getFileName().get(0) + ".gcd"));
			Package newPackage = StatechartModelDerivedFeatures.getContainingPackage(newTopComponent);
			// Top component arguments can now be contained by the Package
			newPackage.getTopComponentArguments().addAll(
					componentReference.getArguments().stream()
					.map(it -> ecoreUtil.clone(it, true, true)).collect(Collectors.toList()));
			// Checking the model whether it contains forbidden elements
			ModelValidator validator = new ModelValidator(newTopComponent, false);
			validator.checkModel();
			// State coverage
			Optional<Coverage> stateCoverage = analysisModelTransformation.getCoverages().stream()
							.filter(it -> it instanceof StateCoverage).findFirst();
			List<SynchronousComponentInstance> testedComponentsForStates = getIncludedSynchronousInstances(
					newTopComponent, stateCoverage);
			// Transition coverage
			Optional<Coverage> transitionCoverage = analysisModelTransformation.getCoverages().stream()
							.filter(it -> it instanceof TransitionCoverage).findFirst();
			List<SynchronousComponentInstance> testedComponentsForTransitions = getIncludedSynchronousInstances(
					newTopComponent, transitionCoverage);
			// Out event coverage
			Optional<Coverage> outEventCoverage = analysisModelTransformation.getCoverages().stream()
							.filter(it -> it instanceof OutEventCoverage).findFirst();
			List<SynchronousComponentInstance> testedComponentsForOutEvents = getIncludedSynchronousInstances(
					newTopComponent, outEventCoverage);
			// Interaction coverage
			Optional<Coverage> interactionCoverage = analysisModelTransformation.getCoverages().stream()
							.filter(it -> it instanceof InteractionCoverage).findFirst();
			List<SynchronousComponentInstance> testedComponentsForInteractions = getIncludedSynchronousInstances(
					newTopComponent, interactionCoverage);
			TestQueryGenerationHandler testGenerationHandler = new TestQueryGenerationHandler(
					testedComponentsForStates, testedComponentsForTransitions,
					testedComponentsForOutEvents, testedComponentsForInteractions);
			if (interactionCoverage.isPresent()) {
				InteractionCoverage coverage = (InteractionCoverage) interactionCoverage.get();
				testGenerationHandler.setInteractionRepresentation(
						getInteractionRepresentation(coverage.getInteractionRepresentation()));
			}
			logger.log(Level.INFO, "Resource set content for flattened Gamma to UPPAAL transformation: " +
					newTopComponent.eResource().getResourceSet());
			Constraint constraint = transformConstraint(analysisModelTransformation.getConstraint(), newTopComponent);
			CompositeToUppaalTransformer transformer = new CompositeToUppaalTransformer(
				newTopComponent,
				getGammaScheduler(analysisModelTransformation.getScheduler().get(0)),
				constraint,
				analysisModelTransformation.isMinimalElementSet(),
				testGenerationHandler); // newTopComponent
			SimpleEntry<NTA, G2UTrace> resultModels = transformer.execute();
			NTA nta = resultModels.getKey();
			// Saving the generated models
			ecoreUtil.normalSave(nta, targetFolderUri, "." + analysisModelTransformation.getFileName().get(0) + ".uppaal");
			ecoreUtil.normalSave(resultModels.getValue(), targetFolderUri, "." + analysisModelTransformation.getFileName().get(0) + ".g2u");
			// Serializing the NTA model to XML
			UppaalModelSerializer.saveToXML(nta, targetFolderUri, analysisModelTransformation.getFileName().get(0) + ".xml");
			// Creating a new query file
			new File(targetFolderUri + File.separator +	analysisModelTransformation.getFileName().get(0) + ".q").delete();
			UppaalModelSerializer.saveString(targetFolderUri, analysisModelTransformation.getFileName().get(0) + ".q", testGenerationHandler.generateExpressions());
			transformer.dispose();
			logger.log(Level.INFO, "The UPPAAL transformation has been finished.");
		}
		
		private List<SynchronousComponentInstance> getIncludedSynchronousInstances(Component component,
				Optional<Coverage> coverage) {
			SimpleInstanceHandler simpleInstanceHandler = SimpleInstanceHandler.INSTANCE;
			if (coverage.isPresent()) {
				Coverage presentCoverage = coverage.get();
				return simpleInstanceHandler.getNewSimpleInstances(presentCoverage.getInclude(),
					presentCoverage.getExclude(), component);
			}
			return Collections.emptyList();
		}
		
		private Constraint transformConstraint(hu.bme.mit.gamma.genmodel.model.Constraint constraint, Component newComponent) {
			if (constraint == null) {
				return null;
			}
			if (constraint instanceof hu.bme.mit.gamma.genmodel.model.OrchestratingConstraint) {
				hu.bme.mit.gamma.genmodel.model.OrchestratingConstraint orchestratingConstraint = (hu.bme.mit.gamma.genmodel.model.OrchestratingConstraint) constraint;
				return new OrchestratingConstraint(orchestratingConstraint.getMinimumPeriod(), orchestratingConstraint.getMaximumPeriod());
			}
			if (constraint instanceof hu.bme.mit.gamma.genmodel.model.SchedulingConstraint) {
				hu.bme.mit.gamma.genmodel.model.SchedulingConstraint schedulingConstraint = (hu.bme.mit.gamma.genmodel.model.SchedulingConstraint) constraint;
				SchedulingConstraint gammaSchedulingConstraint = new SchedulingConstraint();
				for (hu.bme.mit.gamma.genmodel.model.AsynchronousInstanceConstraint instanceConstraint : schedulingConstraint.getInstanceConstraint()) {
					gammaSchedulingConstraint.getInstanceConstraints().addAll(transformAsynchronousInstanceConstraint(instanceConstraint, newComponent));
				}
				return gammaSchedulingConstraint;
			}
			throw new IllegalArgumentException("Not known constraint: " + constraint);
		}
		
		private List<AsynchronousInstanceConstraint> transformAsynchronousInstanceConstraint(
				hu.bme.mit.gamma.genmodel.model.AsynchronousInstanceConstraint asynchronousInstanceConstraint, Component newComponent) {
			if (newComponent instanceof AsynchronousAdapter) {
				// In the case of asynchronous adapters, the referred instance will be null
				return Collections.singletonList(new AsynchronousInstanceConstraint(null,
					(OrchestratingConstraint) transformConstraint(asynchronousInstanceConstraint.getOrchestratingConstraint(), newComponent)));
			}
			// Asynchronous composite components
			SimpleInstanceHandler instanceHandler = SimpleInstanceHandler.INSTANCE;
			List<AsynchronousInstanceConstraint> asynchronousInstanceConstraints = new ArrayList<AsynchronousInstanceConstraint>();
			ComponentInstanceReference originalInstance = asynchronousInstanceConstraint.getInstance();
			List<AsynchronousComponentInstance> newAsynchronousSimpleInstances = instanceHandler
					.getNewAsynchronousSimpleInstances(originalInstance, newComponent);
			for (AsynchronousComponentInstance newAsynchronousSimpleInstance : newAsynchronousSimpleInstances) {
				asynchronousInstanceConstraints.add(new AsynchronousInstanceConstraint(newAsynchronousSimpleInstance,
					(OrchestratingConstraint) transformConstraint(asynchronousInstanceConstraint.getOrchestratingConstraint(), newComponent)));
			}
			return asynchronousInstanceConstraints;
		}
		
		private Scheduler getGammaScheduler(hu.bme.mit.gamma.genmodel.model.Scheduler scheduler) {
			switch (scheduler) {
			case FAIR:
				return Scheduler.FAIR;
			default:
				return Scheduler.RANDOM;
			}
		}
		
		private InteractionRepresentation getInteractionRepresentation(
				hu.bme.mit.gamma.genmodel.model.InteractionRepresentation interactionRepresentation) {
			switch (interactionRepresentation) {
			case OVER_APPROXIMATION:
				return InteractionRepresentation.OVER_APPROXIMATION;
			default:
				return InteractionRepresentation.UNDER_APPROXIMATION;
			}
		}
		
	}
	
	class Gamma2XSTSTransformer extends AnalysisModelTransformer {
		
		protected StatechartUtil statechartUtil = StatechartUtil.INSTANCE;
		protected ActionSerializer actionSerializer = ActionSerializer.INSTANCE;
		
		public void execute(AnalysisModelTransformation analysisModelTransformation) {
			logger.log(Level.INFO, "Starting XSTS transformation.");
			// Unfolding the given system
			ComponentReference componentReference = (ComponentReference) analysisModelTransformation.getModel();
			Component component = componentReference.getComponent();
			Package gammaPackage = (Package) component.eContainer();
			Integer schedulingConstraint = transformConstraint(analysisModelTransformation.getConstraint());
			GammaToXSTSTransformer gammaToXSTSTransformer = new GammaToXSTSTransformer(schedulingConstraint, true, true);
			String fileName = analysisModelTransformation.getFileName().get(0);
			File xStsFile = new File(targetFolderUri + File.separator + fileName + "." + XSTS_XTEXT_EXTENSION);
			XSTS xSts = gammaToXSTSTransformer.preprocessAndExecute(gammaPackage,
					componentReference.getArguments(), xStsFile);
			// EMF
			ecoreUtil.normalSave(xSts, targetFolderUri, fileName + "." + XSTS_EMF_EXTENSION);
			// String
			String xStsString = actionSerializer.serializeXSTS(xSts);
			fileUtil.saveString(xStsFile, xStsString);
			logger.log(Level.INFO, "The XSTS transformation has been finished.");
		}
		
		private Integer transformConstraint(hu.bme.mit.gamma.genmodel.model.Constraint constraint) {
			if (constraint == null) {
				return null;
			}
			if (constraint instanceof hu.bme.mit.gamma.genmodel.model.OrchestratingConstraint) {
				hu.bme.mit.gamma.genmodel.model.OrchestratingConstraint orchestratingConstraint =
						(hu.bme.mit.gamma.genmodel.model.OrchestratingConstraint) constraint;
				TimeSpecification minimumPeriod = orchestratingConstraint.getMinimumPeriod();
				TimeSpecification maximumPeriod = orchestratingConstraint.getMaximumPeriod();
				int min = statechartUtil.evaluateMilliseconds(minimumPeriod);
				int max = statechartUtil.evaluateMilliseconds(maximumPeriod);
				if (min == max) {
					return min;
				}
			}
			throw new IllegalArgumentException("Not known constraint: " + constraint);
		}
	
	}
	
	class XSTS2UppaalTransformer extends AnalysisModelTransformer {
		
		public void execute(AnalysisModelTransformation analysisModelTransformation) {
			logger.log(Level.INFO, "Starting XSTS-UPPAAL transformation.");
			XSTS xSts = (XSTS) GenmodelDerivedFeatures.getModel(analysisModelTransformation);
			String fileName = analysisModelTransformation.getFileName().get(0);
			execute(xSts, fileName);
		}

		public void execute(XSTS xSts, String fileName) {
			XSTSToUppaalTransformer xStsToUppaalTransformer = new XSTSToUppaalTransformer(xSts);
			NTA nta = xStsToUppaalTransformer.execute();
			ecoreUtil.normalSave(nta, targetFolderUri, "." + fileName + ".uppaal");
			// Serializing the NTA model to XML
			UppaalModelSerializer.saveToXML(nta, targetFolderUri, fileName + ".xml");
			// Creating a new query file
			logger.log(Level.INFO, "The transformation has been finished.");
		}
	}
	
	class Gamma2XSTSUppaalTransformer extends AnalysisModelTransformer {
		
		public void execute(AnalysisModelTransformation analysisModelTransformation) {
			logger.log(Level.INFO, "Starting Gamma -> XSTS-UPPAAL transformation.");
			Gamma2XSTSTransformer thetaTransformer = new Gamma2XSTSTransformer();
			thetaTransformer.execute(analysisModelTransformation);
			String fileName = analysisModelTransformation.getFileName().get(0);
			XSTS xSts = (XSTS) ecoreUtil.normalLoad(targetFolderUri, fileName + "." + XSTS_EMF_EXTENSION);
			XSTS2UppaalTransformer xSts2UppaalTransformer = new XSTS2UppaalTransformer();
			xSts2UppaalTransformer.execute(xSts, fileName);
			// Creating a new query file
			logger.log(Level.INFO, "The transformation has been finished.");
		}
	}
	
}
