package app.pockettavern.domain

interface AppActions {
    fun saveProvider(provider: ProviderConfig, key: String)
    fun deleteProvider(id: String)
    fun refreshModels(id: String)
    /** Refreshes the Venice account or API-key balance shown on the model page. */
    fun refreshVeniceBalance(providerId: String) { }
    fun createChat(characterId: String = "")
    fun selectChat(id: String)
    fun updateChat(chat: Chat)
    /** Saves the user-confirmed dynamic scenario ledger for one chat. */
    fun saveScenarioState(chatId: String, state: ScenarioState) { }
    /** Saves the user-confirmed character/relationship roster for one chat. */
    fun saveScenarioRoster(chatId: String, roster: List<ScenarioPerson>) { }
    /** Reads the existing dialogue and updates scenario panels from explicit historical facts. */
    fun organizeScenarioFromHistory(chatId: String) { }
    fun deleteChat(id: String)
    fun repairMessages(chatId: String, messageIds: Set<String>) { }
    fun undoLastChatRepair(chatId: String) { }
    fun chooseModel(providerId: String, modelId: String)
    fun send(text: String): Boolean
    fun sendWithIntent(text: String, intent: String): Boolean = send(text)
    fun setMessageContext(id: String, included: Boolean) { }
    fun saveContinuityNotes(chatId: String, text: String) { }
    /**
     * Runs the session's structured person/world research workflow.  The
     * controller owns networking and persistence; the UI only supplies the
     * user's selected fields and whether the result should become a
     * knowledge note or continuity note.
     */
    fun researchCharacter(
        chatId: String,
        subject: String,
        categories: List<String>,
        useOnline: Boolean,
        allowFiction: Boolean,
        saveToKnowledgeBase: Boolean,
        researchProviderId: String = "",
        researchModelId: String = "",
        searchMode: String = "direct"
    ) { }
    /** Opens the user's own豆包 webpage and runs the research prompt there after login. */
    fun openDoubaoWebResearch(chatId: String, subject: String, categories: List<String>, allowFiction: Boolean) { }
    /** Imports a completed answer returned by the official豆包 webpage activity. */
    fun importDoubaoWebAnswer(
        requestId: String,
        chatId: String,
        subject: String,
        categories: List<String>,
        allowFiction: Boolean,
        text: String,
        pageUrl: String,
        sourceLinks: List<String> = emptyList(),
        mediaDescription: String = ""
    ) { }
    fun saveResearchSearchKey(key: String) { }
    fun applyDeepSeekWriting(chatId: String, effort: String, maxOutput: Int, includePlatformPrompt: Boolean, reasoningBudget: Int = 0, generationBudgetEnabled: Boolean = false) { }
    fun stop()
    fun regenerate()
    fun editMessage(id: String, text: String)
    fun branchAt(messageId: String)
    fun savePreset(preset: Preset)
    fun deletePreset(id: String)
    fun saveCharacter(character: Character)
    fun deleteCharacter(id: String)
    fun saveMemory(memory: Memory)
    fun deleteMemory(id: String)
    fun saveWorldEntry(entry: WorldEntry)
    fun deleteWorldEntry(id: String)
    fun saveSkill(skill: SkillRecord)
    fun setSkillForCurrentChat(id: String, selected: Boolean)
    fun updateChatSkills(chatId: String, skillIds: List<String>, summaries: List<Summary>) { }
    fun installSkillUrl(url: String)
    fun deleteSkill(id: String)
    fun restoreBuiltinSkill(id: String) { }
    fun saveSettings(settings: AppSettings)
    fun confirmAdultSkills(confirmed: Boolean) { }
    fun compress()
    fun rebuildHistory() { }
    fun exportMemoryDiagnostics() { }
    fun restoreSummary(id: String)
    fun previewContext()
    fun dismissPreview()
    fun showDiagnostics() { }
    fun dismissDiagnostics() { }
    fun clearDiagnostics() { }
    fun checkBilling() { }
    fun importFile(kind: String)
    fun exportFile(kind: String, id: String = "")
    fun pickAttachment()
    fun removeAttachment(id: String)
    fun runMedia(providerId: String, modelId: String, requestJson: String)
    fun pollMedia(id: String)
    fun openMedia(id: String)
    fun saveMedia(id: String)
    fun clearStatus()
    /** Sticker packs: import goes through importFile("stickers"). Defaults keep preview fakes compiling. */
    fun deleteStickerPack(id: String) { }
    fun setStickerPackEnabled(id: String, enabled: Boolean) { }
    fun setChatStickerFrequency(chatId: String, frequency: String) { }
    fun stickerFile(sticker: Sticker): java.io.File? = null
}
