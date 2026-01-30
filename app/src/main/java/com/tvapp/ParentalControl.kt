package com.tvapp

import android.content.SharedPreferences

class ParentalControl(private val prefs: SharedPreferences) {
    
    companion object {
        private const val PREF_PARENTAL_CONTROL_ENABLED = "parental_control_enabled"
        private const val PREF_PIN = "parental_control_pin"
        private const val PREF_RESTRICTED_CATEGORIES = "parental_control_restricted_categories"
    }
    
    fun isEnabled(): Boolean {
        return prefs.getBoolean(PREF_PARENTAL_CONTROL_ENABLED, false)
    }
    
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_PARENTAL_CONTROL_ENABLED, enabled).apply()
    }
    
    fun setPIN(pin: String) {
        prefs.edit().putString(PREF_PIN, pin).apply()
    }
    
    fun validatePIN(pin: String): Boolean {
        val storedPin = prefs.getString(PREF_PIN, null) ?: return false
        return storedPin == pin
    }
    
    fun hasPINSet(): Boolean {
        return !prefs.getString(PREF_PIN, null).isNullOrBlank()
    }
    
    fun addRestrictedCategory(categoryId: String) {
        val restricted = getRestrictedCategories().toMutableSet()
        restricted.add(categoryId)
        prefs.edit().putStringSet(PREF_RESTRICTED_CATEGORIES, restricted).apply()
    }
    
    fun removeRestrictedCategory(categoryId: String) {
        val restricted = getRestrictedCategories().toMutableSet()
        restricted.remove(categoryId)
        prefs.edit().putStringSet(PREF_RESTRICTED_CATEGORIES, restricted).apply()
    }
    
    fun getRestrictedCategories(): Set<String> {
        return prefs.getStringSet(PREF_RESTRICTED_CATEGORIES, emptySet()) ?: emptySet()
    }
    
    fun isCategoryRestricted(categoryId: String): Boolean {
        return getRestrictedCategories().contains(categoryId)
    }
    
    fun clearAllRestrictions() {
        prefs.edit().putStringSet(PREF_RESTRICTED_CATEGORIES, emptySet()).apply()
    }
}
