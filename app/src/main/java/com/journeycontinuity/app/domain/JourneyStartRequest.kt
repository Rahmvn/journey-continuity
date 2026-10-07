package com.journeycontinuity.app.domain

/** Frozen, memory-only until JourneyLifecycle admits it. Never reduce this to a label. */
data class JourneyStartRequest(
    val displayLabel: String,
    val destination: StructuredDestination,
    val expectedArrivalAt: Long,
) {
    init { validateDestination() }

    fun validateDestination(catalogue: AdminCatalogue? = null) {
        require(displayLabel.isNotBlank() && displayLabel == displayLabel.trim() &&
            displayLabel.codePointCount(0, displayLabel.length) <= 512 &&
            displayLabel.none { Character.isISOControl(it) }) { "Destination label is invalid." }
        StructuredDestinationValidator.validate(destination, catalogue)
        if (destination.kind == DestinationKind.LOCALITY) {
            require(displayLabel == "${destination.localUnitName}, ${destination.stateName}") {
                "Locality label does not match canonical context."
            }
        }
    }

    override fun toString() = "JourneyStartRequest(redacted)"
}
