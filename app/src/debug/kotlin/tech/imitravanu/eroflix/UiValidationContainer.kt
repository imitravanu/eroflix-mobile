package tech.imitravanu.eroflix

/** In-process instrumentation override only; release has an identity implementation. */
internal object UiValidationContainer { var value: AppContainer?=null;var serviceReady=true }
internal fun playbackServiceReady()=UiValidationContainer.serviceReady
internal fun appContainerForUi(production: AppContainer)=UiValidationContainer.value ?: production
