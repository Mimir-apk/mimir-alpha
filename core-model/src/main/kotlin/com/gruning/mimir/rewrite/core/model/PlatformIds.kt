package com.gruning.mimir.rewrite.core.model

import kotlinx.serialization.Serializable

/** Typed identities shared by the generated platform kernel and its consumers. */

@Serializable
@JvmInline
value class PlatformDefinitionVersion(val value: String) {
    init { require(value.isNotBlank()) { "PlatformDefinitionVersion must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class RegistryRevision(val value: String) {
    init { require(value.isNotBlank()) { "RegistryRevision must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class IdMapVersion(val value: String) {
    init { require(value.isNotBlank()) { "IdMapVersion must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class CatalogEntryId(val value: String) {
    init { require(value.isNotBlank()) { "CatalogEntryId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ContentProfileId(val value: String) {
    init { require(value.isNotBlank()) { "ContentProfileId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ImportBindingId(val value: String) {
    init { require(value.isNotBlank()) { "ImportBindingId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class RetroArchRegistrationId(val value: String) {
    init { require(value.isNotBlank()) { "RetroArchRegistrationId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class EmulatorFamilyId(val value: String) {
    init { require(value.isNotBlank()) { "EmulatorFamilyId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class LaunchRouteId(val value: String) {
    init { require(value.isNotBlank()) { "LaunchRouteId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class PackageVariantId(val value: String) {
    init { require(value.isNotBlank()) { "PackageVariantId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class CoreArtifactEnvelopeId(val value: String) {
    init { require(value.isNotBlank()) { "CoreArtifactEnvelopeId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class DeviceEnvelopeId(val value: String) {
    init { require(value.isNotBlank()) { "DeviceEnvelopeId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class FirmwarePolicyId(val value: String) {
    init { require(value.isNotBlank()) { "FirmwarePolicyId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class VisualAssetKey(val value: String) {
    init { require(value.isNotBlank()) { "VisualAssetKey must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SupportSliceId(val value: String) {
    init { require(value.isNotBlank()) { "SupportSliceId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SourceRootId(val value: String) {
    init { require(value.isNotBlank()) { "SourceRootId must not be blank" } }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ContentMemberId(val value: String) {
    init { require(value.isNotBlank()) { "ContentMemberId must not be blank" } }
    override fun toString(): String = value
}

