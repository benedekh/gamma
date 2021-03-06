/********************************************************************************
 * Copyright (c) 2018-2020 Contributors to the Gamma project
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 ********************************************************************************/
package hu.bme.mit.gamma.xsts.transformation

import hu.bme.mit.gamma.expression.model.VariableDeclaration
import hu.bme.mit.gamma.statechart.composite.BroadcastChannel
import hu.bme.mit.gamma.statechart.composite.CompositeComponent
import hu.bme.mit.gamma.statechart.composite.SimpleChannel
import hu.bme.mit.gamma.util.GammaEcoreUtil
import hu.bme.mit.gamma.xsts.model.AssignmentAction
import hu.bme.mit.gamma.xsts.model.XSTS
import hu.bme.mit.gamma.xsts.model.XSTSModelFactory
import hu.bme.mit.gamma.xsts.util.XSTSActionUtil
import java.util.List

import static com.google.common.base.Preconditions.checkState

import static extension hu.bme.mit.gamma.statechart.derivedfeatures.StatechartModelDerivedFeatures.*
import static extension hu.bme.mit.gamma.xsts.transformation.util.Namings.*

class EventConnector {
	// Singleton
	public static final EventConnector INSTANCE =  new EventConnector
	protected new() {}
	// Auxiliary objects
	protected extension GammaEcoreUtil expressionUtil = GammaEcoreUtil.INSTANCE
	protected extension XSTSActionUtil xStsActionUtil = XSTSActionUtil.INSTANCE
	protected extension XSTSModelFactory xStsModelFactory = XSTSModelFactory.eINSTANCE
	
	def void connectEventsThroughChannels(XSTS xSts, CompositeComponent component) {
		val xStsAssignmentActions = xSts.getAllContentsOfType(AssignmentAction) // Caching
		val xStsDeletableAssignmentActions = newHashSet
		val xStsDeletableVariables = newHashSet
		for (channel : component.channels) {
			val providedPort = channel.providedPort.port
			val requiredPorts = newArrayList
			if (channel instanceof SimpleChannel) {
				requiredPorts += channel.requiredPort.port
			}
			else if (channel instanceof BroadcastChannel) {
				requiredPorts += channel.requiredPorts.map[it.port]
			}
			// Connection: keeping in-variables, deleting out-variables
			val providedSimplePorts = providedPort.allConnectedSimplePorts
			checkState(providedSimplePorts.size == 1)
			val providedSimplePort = providedSimplePorts.head
			val providedStatechart = providedSimplePort.containingStatechart
			val providedInstance = providedStatechart.referencingComponentInstance
			for (requiredPort : requiredPorts) {
				for (requiredSimplePort : requiredPort.allConnectedSimplePorts) {
					val requiredStatechart = requiredSimplePort.containingStatechart
					val requiredInstance = requiredStatechart.referencingComponentInstance
					// In events on required port
					for (event : requiredSimplePort.inputEvents) {
						val requiredInEventName = event.customizeInputName(requiredSimplePort, requiredInstance)
						val xStsInEventVariable = xSts.variableDeclarations.findFirst[it.name == requiredInEventName]
						if (xStsInEventVariable !== null) {
							val providedOutEventName = event.customizeOutputName(providedSimplePort, providedInstance)
							val xStsOutEventVariable = xSts.variableDeclarations.findFirst[it.name == providedOutEventName]
							if (xStsOutEventVariable !== null) { // Can be null due to XSTS optimization
								xStsDeletableAssignmentActions += xStsOutEventVariable.connectEvents(xStsInEventVariable, xStsAssignmentActions)
								xStsDeletableVariables += xStsOutEventVariable
								// In-parameters
								for (parameter : event.parameterDeclarations) {
									val requiredInParamaterName = parameter.customizeInName(requiredSimplePort, requiredInstance)
									val xStsInParameterVariable = xSts.variableDeclarations.findFirst[it.name == requiredInParamaterName]
									if (xStsInParameterVariable !== null) { // Can be null due to XSTS optimization
										val providedOutParamaterName = parameter.customizeOutName(providedSimplePort, providedInstance)
										val xStsOutParameterVariable = xSts.variableDeclarations.findFirst[it.name == providedOutParamaterName]
										if (xStsOutParameterVariable !== null) { // Can be null due to XSTS optimization
											xStsDeletableAssignmentActions += xStsOutParameterVariable.connectEvents(xStsInParameterVariable, xStsAssignmentActions)
											xStsDeletableVariables += xStsOutParameterVariable
										}
									}
								}
							}
						}
					}
					// Out events on required port
					for (event : requiredSimplePort.outputEvents) {
						val requiredOutEventName = event.customizeOutputName(requiredSimplePort, requiredInstance)
						val xStsOutEventVariable = xSts.variableDeclarations.findFirst[it.name == requiredOutEventName]
						if (xStsOutEventVariable !== null) { // Can be null due to XSTS optimization
							val providedInEventName = event.customizeInputName(providedSimplePort, providedInstance)
							val xStsInEventVariable = xSts.variableDeclarations.findFirst[it.name == providedInEventName]
							if (xStsInEventVariable !== null) { // Can be null due to XSTS optimization
								xStsDeletableAssignmentActions += xStsOutEventVariable.connectEvents(xStsInEventVariable, xStsAssignmentActions)
								xStsDeletableVariables += xStsOutEventVariable
								// Out-parameters
								for (parameter : event.parameterDeclarations) {
									val requiredOutParamaterName = parameter.customizeOutName(requiredSimplePort, requiredInstance)
									val xStsOutParameterVariable = xSts.variableDeclarations.findFirst[it.name == requiredOutParamaterName]
									if (xStsOutParameterVariable !== null) { // Can be null due to XSTS optimization
										val providedInParamaterName = parameter.customizeInName(providedSimplePort, providedInstance)
										val xStsInParameterVariable = xSts.variableDeclarations.findFirst[it.name == providedInParamaterName]
										if (xStsInParameterVariable !== null) { // Can be null due to XSTS optimization
											xStsDeletableAssignmentActions += xStsOutParameterVariable.connectEvents(xStsInParameterVariable, xStsAssignmentActions)
											xStsDeletableVariables += xStsOutParameterVariable
										}
									}
								}
							}
						}
					}
				}
			}
		}
		for (xStsDeletableAssignmentAction : xStsDeletableAssignmentActions) {
			xStsDeletableAssignmentAction.remove // To speed up the process
		}
		for (xStsDeletableVariable : xStsDeletableVariables) {
			xStsDeletableVariable.delete // Delete needed due to e.g., transientVariables list
		}
	}
	
	protected def connectEvents(VariableDeclaration xStsOutVariable,
			VariableDeclaration xStsInVariable, List<AssignmentAction> xStsAssignmentActions) {
		val xStsDeletableAssignmentActions = newHashSet
		for (xStsAssignmentAction : xStsAssignmentActions) {
			val xStsDeclaration = xStsAssignmentAction.lhs.declaration
			if (xStsDeclaration === xStsOutVariable) {
				val xStsNewAssignmentAction = xStsAssignmentAction.clone => [
					it.lhs.declaration = xStsInVariable
				]
				xStsAssignmentAction.appendToAction(xStsNewAssignmentAction)
				xStsDeletableAssignmentActions += xStsAssignmentAction
			}
		}
		return xStsDeletableAssignmentActions
	}
	
}