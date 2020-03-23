/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ide.konan

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.KotlinFacetSettingsProvider
import org.jetbrains.kotlin.idea.isMainFunction
import org.jetbrains.kotlin.idea.project.platform
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.util.GradleConstants


class KotlinNativeRunConfigurationProducer :
    LazyRunConfigurationProducer<GradleRunConfiguration>(),
    KotlinNativeRunConfigurationProvider {

    override val isForTests = false

    override fun getConfigurationFactory(): ConfigurationFactory =
        GradleExternalTaskConfigurationType.getInstance().factory

    override fun isConfigurationFromContext(configuration: GradleRunConfiguration, context: ConfigurationContext): Boolean {
        return true
    }

    override fun setupConfigurationFromContext(
        configuration: GradleRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        if (sourceElement.isNull) return false
        val location = context.location ?: return false
        val module = location.module?.asNativeModule() ?: return false

        if (GradleConstants.SYSTEM_ID != configuration.settings.externalSystemId) return false

        val settings = KotlinFacetSettingsProvider.getInstance(module.project)?.getSettings(module) ?: return false
        val runTask = settings.externalSystemNativeRunTasks
            .firstOrNull { it.taskName.startsWith("runDebug", true) }
            ?.taskName ?: return false

        configuration.settings.apply {
            externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(context.module)
            taskNames = listOf(runTask)
        }
        configuration.name = runTask
        configuration.isScriptDebugEnabled = false

        return true
    }

    private fun Module.asNativeModule(): Module? = takeIf { it.platform.isNative() }
}