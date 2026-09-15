package com.jo.selfcontrol.ultimate

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Manages the full-screen Setup & Onboarding UI shown when the app is not fully configured.
 * Gated until all mandatory permissions (Accessibility, Usage Stats, Notifications) are granted.
 * Includes explicit guidance for unlocking Android 13+ "Restricted Settings" (Paramètres Restreints)
 * with specific instructions tailored for Google Pixel, Samsung (One UI), and Xiaomi/Redmi (MIUI/HyperOS).
 */
class SetupScreenManager(
    private val activity: Activity,
    private val onFinishSetup: () -> Unit
) {

    private lateinit var rootView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var brandInstructionsText: TextView
    private lateinit var finishButton: Button
    private lateinit var statusBannerText: TextView

    // Brand pill views for active selection styling
    private val brandPills = mutableMapOf<PermissionHelper.DeviceBrand, TextView>()
    private var selectedBrand = PermissionHelper.getDeviceBrand()

    // Permission row item holders
    private lateinit var a11yRow: PermissionRow
    private lateinit var usageRow: PermissionRow
    private lateinit var postNotifRow: PermissionRow
    private lateinit var notifListenerRow: PermissionRow
    private lateinit var dndRow: PermissionRow
    private lateinit var batteryRow: PermissionRow

    private class PermissionRow(
        val container: LinearLayout,
        val iconText: TextView,
        val titleText: TextView,
        val subtitleText: TextView,
        val actionButton: Button,
        val statusText: TextView
    )

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radiusDp: Int = 16,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    fun buildView(): View {
        rootView = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
        }

        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(48))
        }
        rootView.addView(contentContainer)

        // 1. Header
        buildHeader()

        // 2. Restricted Settings Guide (Android 13+)
        buildRestrictedSettingsSection()

        // 3. Permissions Checklist
        buildPermissionsChecklist()

        // 4. Device Owner Banner (if DO active)
        if (DeviceOwnerHelper.isDeviceOwner(activity)) {
            buildDeviceOwnerBadge()
        }

        // 5. Footer & Unlock Action
        buildFooterAction()

        // Initial refresh
        refresh()

        return rootView
    }

    private fun buildHeader() {
        val title = TextView(activity).apply {
            text = "Bienvenue sur Custos"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val subtitle = TextView(activity).apply {
            text = "Configuration de démarrage requise"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(24))
        }
        contentContainer.addView(title)
        contentContainer.addView(subtitle)
    }

    private fun buildRestrictedSettingsSection() {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#15171C"), 18, Color.parseColor("#2A2E39"), 1)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(activity).apply {
            text = "⚠️"
            textSize = 20f
            setPadding(0, 0, dp(10), 0)
        }
        val cardTitle = TextView(activity).apply {
            text = "Paramètres Restreints (Android 13+)"
            setTextColor(Color.parseColor("#FFCC00"))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(icon)
        headerRow.addView(cardTitle)
        card.addView(headerRow)

        val desc = TextView(activity).apply {
            text = "Sur Android récent, l'activation des services d'accessibilité et d'écoute est bloquée par le système (" +
                    "\"Paramètre restreint indisponible\") pour les applications installées hors store. " +
                    "Voici comment débloquer l'accès selon votre marque :"
            setTextColor(Color.parseColor("#C5C8D1"))
            textSize = 13.5f
            setPadding(0, dp(10), 0, dp(14))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        card.addView(desc)

        // Brand Selector Pills
        val scrollPills = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(12))
        }
        val pillsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for (brand in PermissionHelper.DeviceBrand.values()) {
            val isAutoDetected = brand == PermissionHelper.getDeviceBrand()
            val label = if (isAutoDetected) "${brand.displayName} (Détecté)" else brand.displayName
            val pill = TextView(activity).apply {
                text = label
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                layoutParams = lp
                setOnClickListener { selectBrand(brand) }
            }
            brandPills[brand] = pill
            pillsContainer.addView(pill)
        }
        scrollPills.addView(pillsContainer)
        card.addView(scrollPills)

        // Brand instruction box
        val instructionBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#1C202A"), 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        brandInstructionsText = TextView(activity).apply {
            setTextColor(Color.parseColor("#E0E3EB"))
            textSize = 13f
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        instructionBox.addView(brandInstructionsText)
        card.addView(instructionBox)

        // Direct App Settings button
        val appSettingsBtn = Button(activity).apply {
            text = "⚙️ Ouvrir les Paramètres de Custos"
            background = roundedBackground(Color.parseColor("#2F3BFF"), 12)
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { setMargins(0, dp(14), 0, 0) }
            layoutParams = lp
            setOnClickListener {
                PermissionHelper.openAppDetailsSettings(activity)
            }
        }
        card.addView(appSettingsBtn)

        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(24)) }
        card.layoutParams = layoutParams

        contentContainer.addView(card)
        updateBrandPillsUI()
    }

    private fun selectBrand(brand: PermissionHelper.DeviceBrand) {
        selectedBrand = brand
        updateBrandPillsUI()
    }

    private fun updateBrandPillsUI() {
        for ((brand, pill) in brandPills) {
            val isSelected = brand == selectedBrand
            if (isSelected) {
                pill.background = roundedBackground(Color.WHITE, 16)
                pill.setTextColor(Color.BLACK)
            } else {
                pill.background = roundedBackground(Color.parseColor("#232733"), 16)
                pill.setTextColor(Color.parseColor("#8E92A4"))
            }
        }

        brandInstructionsText.text = when (selectedBrand) {
            PermissionHelper.DeviceBrand.PIXEL ->
                "📱 Sur Google Pixel / Android Stock :\n\n" +
                        "1. Cliquez sur le bouton bleu ci-dessous pour ouvrir les infos de Custos.\n" +
                        "2. En haut à droite de l'écran, appuyez sur les 3 points verticaux (⋮).\n" +
                        "3. Appuyez sur « Autoriser les paramètres restreints ».\n" +
                        "4. Confirmez avec votre empreinte ou code PIN.\n" +
                        "5. Revenez ici : vous pouvez maintenant activer l'Accessibilité !"

            PermissionHelper.DeviceBrand.SAMSUNG ->
                "📱 Sur Samsung (One UI) :\n\n" +
                        "1. Ouvrez les Paramètres de Custos via le bouton ci-dessous.\n" +
                        "2. En haut à droite, appuyez sur les 3 points (⋮) > « Autoriser les paramètres restreints ».\n" +
                        "3. Validez avec votre schéma ou empreinte.\n" +
                        "4. Dans la même page, allez dans « Batterie » et cochez « Non restreinte ».\n" +
                        "5. Revenez ici pour valider vos autorisations."

            PermissionHelper.DeviceBrand.XIAOMI ->
                "📱 Sur Xiaomi / Redmi / POCO (MIUI & HyperOS) :\n\n" +
                        "1. Ouvrez les Paramètres de Custos ci-dessous.\n" +
                        "2. Faites défiler vers le bas et appuyez sur « Autoriser les paramètres restreints » (ou via les 3 points ⋮ en haut).\n" +
                        "3. Activez également l'option « Lancement automatique ».\n" +
                        "4. Dans « Économiseur de batterie », sélectionnez « Pas de restriction ».\n" +
                        "5. Revenez ici pour finaliser l'activation."

            PermissionHelper.DeviceBrand.OTHER ->
                "📱 Sur les autres appareils Android :\n\n" +
                        "1. Ouvrez les Paramètres de Custos via le bouton ci-dessous.\n" +
                        "2. Cherchez le menu (⋮) ou les autorisations spéciales pour autoriser les paramètres restreints.\n" +
                        "3. Désactivez toute optimisation de batterie pour maintenir le service permanent."
        }
    }

    private fun buildPermissionsChecklist() {
        val sectionTitle = TextView(activity).apply {
            text = "📋 Autorisations Nécessaires"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        contentContainer.addView(sectionTitle)

        // 1. Accessibility
        a11yRow = createPermissionRow(
            icon = "🛡️",
            title = "Service d'Accessibilité",
            badge = "OBLIGATOIRE",
            badgeColor = Color.parseColor("#FF5252"),
            subtitle = "Bloque l'accès aux applications non-autorisées, applique le couvre-feu et la Whitelist.",
            actionLabel = "Activer",
            onAction = { PermissionHelper.requestAccessibilityPermission(activity) }
        )
        contentContainer.addView(a11yRow.container)

        // 2. Usage Stats
        usageRow = createPermissionRow(
            icon = "⏱️",
            title = "Statistiques d'utilisation",
            badge = "OBLIGATOIRE",
            badgeColor = Color.parseColor("#FF5252"),
            subtitle = "Nécessaire pour compter le temps passé sur chaque app et déclencher les limites.",
            actionLabel = "Autoriser",
            onAction = { PermissionHelper.requestUsageStatsPermission(activity) }
        )
        contentContainer.addView(usageRow.container)

        // 3. Post Notifications
        postNotifRow = createPermissionRow(
            icon = "🔔",
            title = "Notifications de l'application",
            badge = "OBLIGATOIRE",
            badgeColor = Color.parseColor("#FF5252"),
            subtitle = "Maintient le service d'arrière-plan permanent actif et affiche les alertes de temps.",
            actionLabel = "Autoriser",
            onAction = { PermissionHelper.requestPostNotificationsPermission(activity) }
        )
        contentContainer.addView(postNotifRow.container)

        // 4. Notification Listener
        notifListenerRow = createPermissionRow(
            icon = "🔕",
            title = "Accès aux notifications",
            badge = "RECOMMANDÉ",
            badgeColor = Color.parseColor("#2F3BFF"),
            subtitle = "Permet de couper et masquer les notifications des applications bloquées en couvre-feu.",
            actionLabel = "Activer",
            onAction = { PermissionHelper.requestNotificationListenerPermission(activity) }
        )
        contentContainer.addView(notifListenerRow.container)

        // 5. DND Policy
        dndRow = createPermissionRow(
            icon = "🌙",
            title = "Mode Ne Pas Déranger",
            badge = "OPTIONNEL",
            badgeColor = Color.parseColor("#757575"),
            subtitle = "Active automatiquement le mode Ne Pas Déranger pendant les heures de couvre-feu.",
            actionLabel = "Activer",
            onAction = { PermissionHelper.requestNotificationPolicyPermission(activity) }
        )
        contentContainer.addView(dndRow.container)

        // 6. Battery Exemption
        batteryRow = createPermissionRow(
            icon = "⚡",
            title = "Batterie sans restriction",
            badge = "RECOMMANDÉ",
            badgeColor = Color.parseColor("#2F3BFF"),
            subtitle = "Empêche Android de fermer le service de contrôle lorsque le téléphone est en veille prolongée.",
            actionLabel = "Désactiver",
            onAction = { PermissionHelper.requestIgnoreBatteryOptimizations(activity) }
        )
        contentContainer.addView(batteryRow.container)
    }

    private fun createPermissionRow(
        icon: String,
        title: String,
        badge: String,
        badgeColor: Int,
        subtitle: String,
        actionLabel: String,
        onAction: () -> Unit
    ): PermissionRow {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#14161C"), 14, Color.parseColor("#222631"), 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            layoutParams = lp
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = TextView(activity).apply {
            text = icon
            textSize = 20f
            setPadding(0, 0, dp(10), 0)
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val titleView = TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val badgeView = TextView(activity).apply {
            text = badge
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(badgeColor)
            background = roundedBackground(Color.argb(40, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)), 6)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, 0, 0) }
            layoutParams = lp
        }
        titleCol.addView(titleView)
        titleCol.addView(badgeView)

        val statusView = TextView(activity).apply {
            text = "✅ Actif"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        val actionBtn = Button(activity).apply {
            text = actionLabel
            background = roundedBackground(Color.parseColor("#2F3BFF"), 10)
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { onAction() }
        }

        topRow.addView(iconView)
        topRow.addView(titleCol)
        topRow.addView(statusView)
        topRow.addView(actionBtn)
        container.addView(topRow)

        val subView = TextView(activity).apply {
            text = subtitle
            setTextColor(Color.parseColor("#8E92A4"))
            textSize = 12.5f
            setPadding(dp(30), dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        container.addView(subView)

        return PermissionRow(container, iconView, titleView, subView, actionBtn, statusView)
    }

    private fun buildDeviceOwnerBadge() {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Color.parseColor("#0F2218"), 14, Color.parseColor("#1B4D32"), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(16)) }
            layoutParams = lp
        }
        val icon = TextView(activity).apply {
            text = "👑"
            textSize = 20f
            setPadding(0, 0, dp(10), 0)
        }
        val txt = TextView(activity).apply {
            text = "Mode Device Owner actif sur cet appareil"
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(icon)
        card.addView(txt)
        contentContainer.addView(card)
    }

    private fun buildFooterAction() {
        val footerBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(24))
        }

        statusBannerText = TextView(activity).apply {
            text = "Veuillez valider les autorisations obligatoires..."
            setTextColor(Color.parseColor("#FFCC00"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        footerBox.addView(statusBannerText)

        finishButton = Button(activity).apply {
            text = "Accéder à Custos →"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = roundedBackground(Color.WHITE, 14)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            )
            layoutParams = lp
            setOnClickListener {
                if (PermissionHelper.isMandatorySetupComplete(activity)) {
                    onFinishSetup()
                }
            }
        }
        footerBox.addView(finishButton)

        contentContainer.addView(footerBox)
    }

    /**
     * Re-evaluates all permissions and updates UI checkmarks and actions.
     * Called on start, onResume, and during the periodic poll loop.
     */
    fun refresh() {
        val hasA11y = PermissionHelper.hasAccessibilityPermission(activity)
        val hasUsage = PermissionHelper.hasUsageStatsPermission(activity)
        val hasPostNotif = PermissionHelper.hasPostNotificationsPermission(activity)
        val hasNotifListener = PermissionHelper.hasNotificationListenerPermission(activity)
        val hasDnd = PermissionHelper.hasNotificationPolicyPermission(activity)
        val hasBattery = PermissionHelper.isIgnoringBatteryOptimizations(activity)

        updateRowState(a11yRow, hasA11y)
        updateRowState(usageRow, hasUsage)
        updateRowState(postNotifRow, hasPostNotif)
        updateRowState(notifListenerRow, hasNotifListener)
        updateRowState(dndRow, hasDnd)
        updateRowState(batteryRow, hasBattery)

        val mandatoryMissing = mutableListOf<String>()
        if (!hasA11y) mandatoryMissing.add("Accessibilité")
        if (!hasUsage) mandatoryMissing.add("Statistiques d'utilisation")
        if (!hasPostNotif) mandatoryMissing.add("Notifications")

        if (mandatoryMissing.isEmpty()) {
            statusBannerText.text = "🎉 Toutes les autorisations indispensables sont prêtes !"
            statusBannerText.setTextColor(Color.parseColor("#4CAF50"))
            finishButton.isEnabled = true
            finishButton.alpha = 1f
            finishButton.text = "Accéder à Custos →"
            finishButton.background = roundedBackground(Color.WHITE, 14)
            finishButton.setTextColor(Color.BLACK)
        } else {
            statusBannerText.text = "⏳ Il reste ${mandatoryMissing.size} étape(s) indispensable(s) : ${mandatoryMissing.joinToString(", ")}"
            statusBannerText.setTextColor(Color.parseColor("#FF5252"))
            finishButton.isEnabled = false
            finishButton.alpha = 0.4f
            finishButton.text = "Configuration incomplète"
            finishButton.background = roundedBackground(Color.parseColor("#2A2E39"), 14)
            finishButton.setTextColor(Color.parseColor("#8E92A4"))
        }
    }

    private fun updateRowState(row: PermissionRow, isGranted: Boolean) {
        if (isGranted) {
            row.statusText.visibility = View.VISIBLE
            row.actionButton.visibility = View.GONE
            row.container.background = roundedBackground(Color.parseColor("#0F1C14"), 14, Color.parseColor("#1B3D28"), 1)
        } else {
            row.statusText.visibility = View.GONE
            row.actionButton.visibility = View.VISIBLE
            row.container.background = roundedBackground(Color.parseColor("#14161C"), 14, Color.parseColor("#222631"), 1)
        }
    }
}

