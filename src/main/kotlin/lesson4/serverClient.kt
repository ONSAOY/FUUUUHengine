package lesson4

import de.fabmax.kool.modules.ui2.mutableStateOf
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.*
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*
import org.w3c.dom.Text
import kotlin.String

// Клиент не должен иметь возможность напрямую менять состояние здоровья, золота, квеста, диалога и тд
// Клиент должен только отправлять запрос на сервер, сервер отрабатывает его запрос и возвращает ему уже измененное свойство (или отказ и бан)
// 1. Клиент отправляет команду-событие о том что что-то произошло в игре
// 2. Сервер дает ему ответ который клиент уже без возможности подменить - отображает на экране
// Если этим принебрегать игру можно будет взломать, изменить, и получить преимущество, либо просто сломать ее

enum class QuestState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

class Npc(
    val id: String,
    val npcName: String
){
    fun dialogueFor(state: QuestState): DialogueView{
        return when (state){
            QuestState.START -> DialogueView(
                npcName,
                "Привет нажми Talk чтобы поговорить",
                listOf(
                    DialogueOption("Talk", "Поговорить")
                )
            )
            QuestState.OFFERED -> DialogueView(
                npcName,
                "Будешь помогать или будем драться?",
                listOf(
                    DialogueOption("help", "Помочь"),
                    DialogueOption("threat", "Драться")
                )
            )
            QuestState.HELP_ACCEPTED -> DialogueView(
                npcName,
                "Красава марат",
                emptyList()
            )
            QuestState.THREAT_ACCEPTED -> DialogueView(
                npcName,
                "Are you sure?",
                listOf(
                    DialogueOption("threat_confirm", "Файт зе повер")
                )
            )
            QuestState.GOOD_END -> DialogueView(
                npcName,
                "Мы уже все решили, гуляй мальчик",
                emptyList()
            )
            QuestState.EVIL_END -> DialogueView(
                npcName,
                "Не хочу драться, вали от сюда",
                emptyList()
            )

        }
    }
}

class ClientUiState{
    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val questState = mutableStateOf(QuestState.START)
    val networkLagMs = mutableStateOf(350)
    // Между сервером и клиентом всегда есть хоть и минимальный но пинг

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: ClientUiState, text: String){
    ui.log.value = (ui.log.value + text).takeLast(20)
}

sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: QuestState
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

typealias Listeners = (GameEvent) -> Unit

class EventBus{
    // (GameEvent) -> Unt: функция принимающая GameEvent возвращает либо Unit либо ничего
    private val listeners = mutableListOf<Listeners>()

    fun subscribe(listener: Listeners){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for (listener in listeners){
            listener(event)
        }
    }
}

////////////////////////////////////////////////////////////
// Команды - запрос клиента к серверу///////////////////////
////////////////////////////////////////////////////////////

sealed interface GameCommand{
    val playerId: String
}

data class CmdTalkToNpc(
    override val playerId: String,
    val npcId: String
): GameCommand

data class CmdSelectedChoice(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameCommand

data class CmdLoadPlayer(
    override val playerId: String
): GameCommand

// SERVER WORLD - серверные данные и обработка клиентских команд

// PlayerData - серверное состояние игрока
data class PlayerData(
    var hp: Int,
    var gold: Int,
    val questState: QuestState
)

// PendingCommand - команда которая ждет своего выполнения(симуляция пинга)
data class PendingCommand(
    val cmd: GameCommand,
    var delayLeftSec: Float
    // Сколько секунд осталось до выполнения команд
)

class ServerWorld(
    private val bus: EventBus
){
    private val questId = "q_alchemist"

    private val inbox = mutableListOf<PendingCommand>()

    private val serverPlayers = mutableMapOf<String, PlayerData>()

    private fun ensurePlayer(playerId: String): PlayerData{
        val existing = serverPlayers[playerId]
        if (existing != null) return existing

        val created = PlayerData(
            100,
            0,
            QuestState.START
        )
        serverPlayers[playerId] = created
        return created
    }

    // Снимок серверных данных
    fun getSnapshot(playerId: String): PlayerData{
        val p = ensurePlayer(playerId)

        return PlayerData(
            p.hp,
            p.gold,
            p.questState
        )
    }

    fun sendCommand(cmd: GameCommand, networkLagMs: Int){
        val lagSec = networkLagMs / 1000f
        inbox.add(
            PendingCommand(
                cmd,
                lagSec
            )
        )
    }

    // Вызываем каждый кадр
    fun update(deltaSec: Float){
        for (pending in inbox){
            pending.delayLeftSec -= deltaSec
        }

        val ready = inbox.filter { it.delayLeftSec <= 0f }
        inbox.removeAll(ready)

        for (pending in ready){
            applyCommand(pending.cmd)
        }
    }
}