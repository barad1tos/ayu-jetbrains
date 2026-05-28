package com.nasller.codeglance.config

class CodeGlanceConfigService {
    private val state = CodeGlanceConfig()

    fun getState(): CodeGlanceConfig = state
}

@Suppress("unused") // Called reflectively by CodeGlanceProIntegration.
class CodeGlanceConfig {
    var viewportColor: String = ""
        private set

    var viewportBorderColor: String = ""
        private set

    var viewportBorderThickness: Int = -1
        private set

    fun setViewportColor(value: String) {
        viewportColor = value
    }

    fun setViewportBorderColor(value: String) {
        viewportBorderColor = value
    }

    fun setViewportBorderThickness(value: Int) {
        viewportBorderThickness = value
    }
}
