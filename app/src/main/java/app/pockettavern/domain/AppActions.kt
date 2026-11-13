package app.pockettavern.domain

interface AppActions {
    fun saveProvider(provider: ProviderConfig, key: String)
    fun deleteProvider(id: String)
    fun refreshModels(id: String)
    /** Refreshes the Venice account or API-key balance shown on the model page. */
    fun refreshVeniceBalance(providerId: String) { }
    fun createChat(characterId: String = "")
    /** Opens a chat on top of the chat list. */
    fun selectChat(id: String)
    /** Back to the chat list; whatever the chat is doing keeps running. */
    fun closeChat() { }
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
    /** Stops what the open chat is doing. Other chats keep going. */
    fun stop()
    /** Stops the long task of one chat, whichever chat is open. */
    fun stopChat(chatId: String) { }
    /** Stops the app-wide task, such as a model refresh. */
    fun stopAppTask() { }
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
    /** Folds the open chat's unfolded history into chapters (all but the recent window). */
    fun compress()
    /** Works out what [chatId]'s next request would send; the result lands in UiSession.contextInspection. */
    fun inspectContext(chatId: String) { }
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
    /** 自己刷视频: 0 turns it off, 1–3 is how many times a day. */
    fun setChatBrowse(chatId: String, perDay: Int) { }
    /** Browses once now, as the schedule would. */
    fun browseNow(chatId: String) { }
    /** Opens a 角色卡助手 chat on the PC; false when no PC model can call tools. */
    fun startCharacterBuilder(): Boolean = false
    /** A character's avatar picture; blocking, call off the main thread. */
    fun characterAvatar(avatar: String): androidx.compose.ui.graphics.ImageBitmap? = null
    fun pickCharacterAvatar(characterId: String) { }
    fun removeCharacterAvatar(characterId: String) { }
    /** A chat's background and how it is toned down; null removes it. Takes effect at once. */
    fun setChatLook(chatId: String, look: ChatLook?) { }
    /** Writes a character card from a short idea (and the form so far) with the given model. */
    fun draftCharacter(idea: String, base: Character, providerId: String, modelId: String) { }
    fun cancelCharacterDraft() { }
    /** Forgets a finished draft once the form has taken it. */
    fun clearCharacterDraft() { }
    /** Creates and opens a group chat; false, with the reason in the status line, when it cannot. */
    fun createGroupChat(title: String, setup: GroupSetup): Boolean = false
    /** Changes a group's name, members, scene, relations or speaking mode; applies from the next reply. */
    fun updateGroup(chatId: String, title: String, setup: GroupSetup): Boolean = false
    /** Opens the gallery; the picture becomes [chatId]'s background. */
    fun pickChatBackground(chatId: String) { }
    /** The background new chats with this character start with; null for none. */
    fun setCharacterLook(characterId: String, look: ChatLook?) { }
    /** A chosen background picture, decoded small for the blur level; blocking, call off the main thread. */
    fun chatBackground(value: String, blur: Int): androidx.compose.ui.graphics.ImageBitmap? = null
    fun pickUserAvatar() { }
    fun removeUserAvatar() { }
    fun setShowUserAvatar(show: Boolean) { }
    fun stickerFile(sticker: Sticker): java.io.File? = null
    /** A sticker image from the PC, fetched once and then cached; blocking, call off the main thread. */
    fun pcStickerFile(sticker: Sticker): java.io.File? = null
    /** Moves packs imported before 0.1.71 from the phone to the PC. */
    fun migrateLocalStickers() { }
    /** Settings → 电脑连接: asks the PC writing server for its status. */
    fun checkPcLink() { }
    fun backupToPc() { }
    fun loadPcBackups() { }
    /** Sends the current data to the PC as a safety copy, then restores [backupId] through the normal restore. */
    fun restoreFromPc(backupId: String) { }
    fun setPcAutoBackup(enabled: Boolean) { }
    fun choosePcProvider(providerId: String) { }
    /** Reads the PC's phone-editable settings for the settings page. */
    fun loadPcSettings() { }
    /** Sends what differs between [original] and [edited]; the PC applies it at once. */
    fun savePcSettings(original: PcSettings, edited: PcSettings) { }
}
