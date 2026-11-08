package com.tomatoinsight.data

data class ProfileNameParts(
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = ""
) {
    val displayName: String
        get() = listOf(firstName, middleName, lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}

fun parseProfileName(displayName: String?): ProfileNameParts {
    if (displayName.isNullOrBlank()) return ProfileNameParts()

    val parts = displayName.trim().split(Regex("\\s+"))
    return when (parts.size) {
        1 -> ProfileNameParts(firstName = parts[0])
        2 -> ProfileNameParts(firstName = parts[0], lastName = parts[1])
        else -> ProfileNameParts(
            firstName = parts.first(),
            middleName = parts.subList(1, parts.lastIndex).joinToString(" "),
            lastName = parts.last()
        )
    }
}

/** Prefer non-blank cloud fields without wiping filled local profile values. */
fun mergeFarmerProfiles(local: FarmerProfile, cloud: FarmerProfile): FarmerProfile {
    return FarmerProfile(
        firstName = cloud.firstName.trim().ifBlank { local.firstName.trim() },
        middleInitial = cloud.middleInitial.trim().ifBlank { local.middleInitial.trim() }
            .uppercase()
            .take(1),
        lastName = cloud.lastName.trim().ifBlank { local.lastName.trim() },
        farmName = cloud.farmName.trim().ifBlank { local.farmName.trim() },
        email = cloud.email.trim().ifBlank { local.email.trim() },
        photo = cloud.photo ?: local.photo
    )
}

/** Fill blank name/email from Firebase Auth when Firestore profile is still empty. */
fun FarmerProfile.withAuthFallback(displayName: String?, email: String?): FarmerProfile {
    val parsed = parseProfileName(displayName)
    return copy(
        firstName = firstName.trim().ifBlank { parsed.firstName },
        middleInitial = middleInitial.trim().ifBlank {
            parsed.middleName.trim().uppercase().take(1)
        },
        lastName = lastName.trim().ifBlank { parsed.lastName },
        email = this.email.trim().ifBlank { email?.trim().orEmpty() }
    )
}
