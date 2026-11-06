package com.ai.assistance.operit.core.team

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 团队 AI 服务：
 * 1. 根据用户目的，由 AI 自动设计团队成员（可指定模型）
 * 2. 为每个成员生成真实回复（可按成员绑定模型）
 */
object TeamAiService {

    private const val TAG = "TeamAiService"

    private val DESIGNER_PROMPT =
            """
            你是一个多智能体团队的架构师。用户会给你一个目标，你需要设计一个能协作完成该目标的 AI 团队。

            要求：
            1. 成员数量 2-6 个，只要能覆盖目标即可，不要臃肿
            2. 每个成员必须分工明确，职责不重叠
            3. 第一个成员是主控（facilitator），负责理解需求、拆解任务、协调其余成员
            4. 每个成员要有：名称（2-6 个字中文）、一个代表性 emoji、详细角色设定（systemPrompt）

            只输出 JSON 数组，不要任何解释、不要 markdown 代码块。格式：
            [{"name":"名称","emoji":"🤖","systemPrompt":"角色设定","isFacilitator":true}]
            """.trimIndent()

    /** 可选模型列表 */
    data class ModelOption(val id: String, val label: String)

    /** 列出可用的模型配置 */
    suspend fun listModels(context: Context): List<ModelOption> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val manager = ModelConfigManager(context)
                    manager.getAllConfigSummaries().map { s ->
                        ModelOption(id = s.id, label = s.name.ifBlank { s.modelName.ifBlank { s.id } })
                    }
                }.getOrElse { emptyList() }
            }

    /** 自动设计团队成员。modelId 为空时用 CHAT 绑定模型 */
    suspend fun autoDesignMembers(
            context: Context,
            goal: String,
            modelId: String? = null
    ): List<TeamMember> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val reply =
                            callModel(
                                    context = context,
                                    systemPrompt = DESIGNER_PROMPT,
                                    userMessage = "目标：" + goal,
                                    modelId = modelId
                            )
                    parseMembers(reply)
                }.getOrElse { e ->
                    AppLogger.e(TAG, "autoDesignMembers failed", e)
                    emptyList()
                }
            }

    /** 为某个成员生成回复，优先用成员自己绑定的模型 */
    suspend fun memberReply(
            context: Context,
            member: TeamMember,
            teamMembers: List<TeamMember>,
            history: List<TeamMessage>,
            userInput: String
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val roster = teamMembers.joinToString("\n") {
                "- " + it.name + (if (it.isFacilitator) "（主控）" else "") + "：" + it.systemPrompt
            }
            val system = buildString {
                appendLine(member.systemPrompt)
                appendLine()
                appendLine("你正在一个多智能体团队群聊中。团队成员：")
                appendLine(roster)
                appendLine()
                appendLine("规则：")
                appendLine("1. 只从你自己的角色出发回答，不要代替其他成员发言")
                appendLine("2. 简洁直接，不要客套寒暄")
                appendLine("3. 如果需要其他成员配合，可以在回答末尾写 @成员名 说明你需要什么")
            }

            val historyText = history.takeLast(20).joinToString("\n") { msg ->
                msg.memberName + "：" + msg.content
            }

            val userText = buildString {
                if (historyText.isNotEmpty()) {
                    appendLine("【群聊记录】")
                    appendLine(historyText)
                    appendLine()
                }
                appendLine("【最新消息】")
                append(userInput)
            }

            callModel(context, system, userText, member.modelId)
        }.getOrElse { e ->
            AppLogger.e(TAG, "memberReply failed for " + member.name, e)
            "（" + member.name + " 暂时无法回复：" + (e.message ?: "未知错误") + "）"
        }
    }

    /** 调用模型。modelId 为空时优先 CHAT 绑定，再退第一个非默认配置 */
    private suspend fun callModel(
            context: Context,
            systemPrompt: String,
            userMessage: String,
            modelId: String? = null
    ): String {
        val manager = ModelConfigManager(context)
        val summaries = manager.getAllConfigSummaries()
        if (summaries.isEmpty()) {
            throw IllegalStateException("还没有配置任何模型，请先到「助手配置」里添加模型")
        }

        val resolvedId =
                modelId?.takeIf { id -> summaries.any { it.id == id } }
                        ?: runCatching {
                                    FunctionalConfigManager(context)
                                            .getConfigIdForFunction(FunctionType.CHAT)
                                }
                                .getOrNull()
                                ?.takeIf { id -> summaries.any { it.id == id } }
                        ?: summaries.firstOrNull { it.id != ModelConfigManager.DEFAULT_CONFIG_ID }?.id
                        ?: summaries.first().id

        val config = manager.getModelConfig(resolvedId)
                ?: throw IllegalStateException("模型配置读取失败（id=" + resolvedId + "）")

        if (config.apiKey.isBlank()) {
            throw IllegalStateException(
                    "模型「" + config.name + "」没有填写 API Key，请到「助手配置」里补上"
            )
        }

        val service = AIServiceFactory.createService(config, manager, context)

        val history =
                listOf(
                        PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt),
                        PromptTurn(kind = PromptTurnKind.USER, content = userMessage)
                )

        val builder = StringBuilder()
        service.sendMessage(
                context = context,
                chatHistory = history,
                stream = false,
                recordTokenUsage = false
        ).collect { chunk -> builder.append(chunk) }

        return builder.toString().trim()
    }

    /** 解析成员 JSON */
    private fun parseMembers(raw: String): List<TeamMember> {
        var text = raw.trim()
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        text = text.substring(start, end + 1)

        val arr = JSONArray(text)
        val result = mutableListOf<TeamMember>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            result.add(
                    TeamMember(
                            id = "ai_" + System.currentTimeMillis() + "_" + i,
                            name = name,
                            emoji = obj.optString("emoji", "🤖").ifBlank { "🤖" },
                            systemPrompt = obj.optString("systemPrompt", ""),
                            isFacilitator = obj.optBoolean("isFacilitator", i == 0)
                    )
            )
        }
        return result
    }
}
