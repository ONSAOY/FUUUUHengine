package lesson3

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

enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
}

// Создание класса с описанием предмета
data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

// Класс описывающий стак предмета
data class ItemStack(
    val item: Item,
    val count: Int
)

val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    type = ItemType.POTION,
    12
)

val SWORD = Item(
    "sword",
    "Sword",
    type = ItemType.WEAPON,
    1
)

class GameState{
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)

    // Хотбар на 9 слотов, List<ItemStack?> - в ячейку хотбара можно положить только стак какого-то предмета
    val hotbar = mutableStateOf(
        List<ItemStack?>(9){null}
        // По умолчанию хотбар заполнен пустыми ячейками
    )
    // Активный слот инвентаря
    val selectedSlot = mutableStateOf(0)

    val eventLog = mutableStateOf<List<String>>(emptyList())
}

// Интерфейс
sealed interface GameEvent {
    val playerId: String
}

// Интерфейс - это как договор или набор правил:
// Интерфейс говорит, если ты хочешь быть событием x, у тебя должны быть перечисленные в интерфейсе методы, свойства и поля
// Если в интерфейс, который мв наследуем лежит свойство или метод, мы обязаны его реализовать
// Если, ты хочешь иметь паспорт, это договор по которому у человека должны быть: имя, дата и тд
// Интерфейс не говорит нам как и что делать, он строго заявляет что должно быть чтобы им пользоваться

interface Entity{
    val id: String
    var x: Float
    var y: Float
}

class Player(
    override val id: String,
    override var x: Float,
    override var y: Float
) : Entity

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int,
    val leftOver: Int
) : GameEvent

// data class - класс который предназначен для хранения данных
// Kotlin - автоматически когда мы помечаем класс "data" - добавляет кучу полезных функций
// метод toString() - способ превращать объект в строку для печати и логов

class A(val x: Int)
// private (A(5))
// Он выведет обработку класса с данными
// мусор для нас и логов

// метод equals() - сравнивает одинаковы ли 2 объекта
// Классы часто сравнивают объекты по их ссылкам
// Дата класс сравнивает по значениям этих объектов
// copy() - копирует и меняет в копии нужные значения и поля

data class ItemUsed(
    override val playerId: String,
    val itemId: String
) : GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
) : GameEvent

data class ItemDeleted(
    override val playerId: String,
    val itemId: String,
    val amount: Int
) : GameEvent

data class QestStepComplited(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int
) : GameEvent

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

class QuestSystem(
    private val bus: EventBus
){
    val questId = "q_training"

    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    private fun getStep(playerId: String): Int{
        return progressByPlayer.value[playerId] ?: 0
    }

    private fun setStep(playerId: String, step: Int){
        val newMap = progressByPlayer.value.toMutableMap()
        newMap[playerId] = step
        progressByPlayer.value = newMap.toMap()
    }

    private fun completeStep(playerId: String, stepIndex: Int){
        setStep(playerId, stepIndex + 1)

        bus.publish(
            QestStepComplited(
                playerId,
                questId,
                stepIndex
            )
        )
    }

    private fun  handleEvent(event: GameEvent){
        val player = event.playerId
        val step = getStep(player)

        if (step >= 2) return
        when(event){
            is ItemAdded -> {
                if (step == 0 && event.itemId == SWORD.id){
                    completeStep(player, 0)
                }
            }
            is DamageDealt -> {
                if (step == 1 && event.targetId == "dummy" && event.amount >= 10){
                    completeStep(player, 1)
                }
            }
            else -> {}
        }
    }
}

fun deleteItem(
    slots: List<ItemStack?>,
    slotsIndex: Int,
    item: Item,
    bus: EventBus,
    playerId: String
): List<ItemStack?>{
    // - предмета

    val newSlots = slots.toMutableList() // делаем копию списка слотов для его редактирования
    val current = newSlots[slotsIndex]
    val amount = 1
    val playerId

//    val toRemove = minOf(amount, item.maxStack)
//    val delSpace: Int

    if (current == null){
        // Если слот куда хотим положить - пуст, создаем в нем новый стак
        println("Брочачос, нечего тебе выкидывать")
        return newSlots
    }

    newSlots[slotsIndex] = null

//    if (current.item.id == item.id){
//        delSpace = amount - item.maxStack
//    }

    bus.publish(
        ItemDeleted(
            playerId,
            itemId,
            amount
        )
    )

    return newSlots
}

fun putIntoSlot(
    slots: List<ItemStack?>,
    slotsIndex: Int,
    item: Item,
    addCount: Int
): Pair<List<ItemStack?>, Int>{
    // Возвращаем измененный список, но уже с новым предметом
    val newSlots = slots.toMutableList() // делаем копию списка слотов для его редактирования
    val current = newSlots[slotsIndex]   // Текущий стак в слоте, может быть null

    if (current == null){
        // Если слот куда хотим положить - пуст, создаем в нем новый стак
        val count = minOf(addCount, item.maxStack)
        newSlots[slotsIndex] = ItemStack(item, count)
        val leftOver = addCount - count
        return Pair(newSlots, leftOver)
    }

    // Если слот в который кладем - не пуст, стакаем предметы если они того же типа
    if (current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        // Отнимаем от количества уже лежащих в стаке предметов от максимально допустимого количества в стаке
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotsIndex] = ItemStack(item, current.count + toAdd)
        val leftOver = addCount - freeSpace
        return Pair(newSlots, leftOver)
    }
    return Pair(newSlots, addCount)
}

fun useSelected(
    slots: List<ItemStack?>,
    slotsIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    // Пара значений - нужна для того, чтобы:
    // 1 функция могла вернуть 2 результата сразу
    // Мы сейчас возвращаем 2 значения, новый хотбар + информацию о том, сколько в него не влезло

    val newSlots = slots.toMutableList()
    val current = newSlots[slotsIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0){
        // Если стало 0 - значит после использования предмета, стак закончится и слот станет пустым
        newSlots[slotsIndex] = null
    }else{
        newSlots[slotsIndex] = ItemStack(current.item, newCount)
    }

    return Pair(newSlots, current)
}

fun pushLog(game: GameState, text: String){
    val old = game.eventLog.value
    val updated = old + text
    game.eventLog.value = updated.takeLast(20)
    // takeLast - обрезает список строк и оставляет последние n
}

fun main() = KoolApplication{
    val game = GameState()
    val bus = EventBus()
    val quest = QuestSystem(bus)
    bus.subscribe { event ->
        val line = when (event){
            is ItemAdded -> "Предмет добавлен: ${event.itemId} + ${event.countAdded} Осталось: ${event.leftOver}"
            is ItemUsed -> "Предмет использован: ${event.itemId}"
            is DamageDealt -> "${event.playerId} нанес ${event.amount} урона ${event.targetId}"
            is EffectApplied -> "Эффект ${event.effectId} наложен на ${event.ticks} тиков"
            is QestStepComplited -> "Шаг ${event.stepIndex + 1} квеста ${event.questId}"
            is ItemDeleted ->  "Предмет удален: ${event.itemId}"
        }

        pushLog(game, "[${event.playerId} $line]")
    }

    // Сцена UI
    addScene {
        defaultOrbitCamera()

//        addColorMesh {
//            generate { cube{colored()} }
//
//            shader = KslPbrShader {
//                color { vertexColor() }
//                metallic(0.7f)
//                roughness(0.10f)
//            }
//            onUpdate{
//                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
//            }
//        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, 1f))
            setColor(Color.WHITE, 7f)
        }

        var potionTimerSec = 0f

        onUpdate{
            if (game.potionTicksLeft.value > 0){
                potionTimerSec += Time.deltaT

                if (potionTimerSec >= 1f){
                    potionTimerSec = 0f

                    game.potionTicksLeft.value --

                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)

                }
            }else{
                potionTimerSec = 0f
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)
        // накладываем поверх нашей сцены новый слой HUD сцены

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 14.dp))
                .padding(12.dp)
            // dp - (density - independent pixel) - условный пиксель, который адаптируется под плотность писелей на разных экранах
            // То есть в отличие от px, dp - физичерски выглядит одинакого на разных устройствах

            Column {
                Text("Игрок: ${game.playerId.use()}"){}
                Text("Hp: ${game.hp.use()}"){}
                Text("Голда: ${game.gold.use()}"){}
                Text("Действие зелья: ${game.potionTicksLeft.use()}"){}
                Text("Здоровье Маникена"){}

                val progress = quest.progressByPlayer.use()[game.playerId.use()]
                val questText = when(progress){
                    0 -> "Квест: получите меч"
                    1 -> "Квест: ударьте маникен мечем"
                    else -> "Квест завершен"
                }

                Row {
                    modifier.margin(top = 6.dp)

                    val slots = game.hotbar.use()
                    val selected = game.selectedSlot.use()

                    for (i in 0 until 9){
                        val isSelected = (i == selected)

                        Box {
                            modifier
                                .size(44.dp, 44.dp)
                                .margin(end = 6.dp)
                                .background(
                                    RoundRectBackground(
                                        if (isSelected) Color(0.2f, 0.2f, 1f, 0.8f) else Color(0f, 0f, 0f, 0.35f),
                                        8.dp
                                    )
                                )
                                .onClick{
                                    game.selectedSlot.value = i
                                }
                            val stack = slots[i]
                            if (stack == null){
                                Text(""){}
                            }else {
                                Column {
                                    modifier.padding(6.dp)

                                    Text("${stack.item.name}"){
                                        modifier.font(sizes.smallText)
                                    }

                                    Text("${stack.count}"){
                                        modifier.font(sizes.smallText)
                                    }
                                }
                            }
                        }
                    }
                }

                Row {
                    Button ("Получить меч"){
                        modifier.margin(end = 8.dp).onClick{
                            val pid = game.playerId.value
                            val idx = game.selectedSlot.value

                            val (updated, leftOver) = putIntoSlot(game.hotbar.value, idx, SWORD, 1)
                            game.hotbar.value = updated

                            bus.publish(ItemAdded(pid, SWORD.id, 1, leftOver))
                        }
                    }
                    Button ("Выбросить "){
                        modifier.margin(end = 8.dp).onClick{
                            val pid = game.playerId.value
                            val idx = game.selectedSlot.value
                            val playerId

                            addColorMesh {
                                generate { cube{colored()} }

                                shader = KslPbrShader {
                                    color { vertexColor() }
                                    metallic(0.7f)
                                    roughness(0.10f)
                                }
                                onUpdate{
                                    transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
                                }
                            }

                            bus.publish(ItemDeleted(pid, SWORD.id, 1,))
                        }
                    }
                }
            }
        }
    }
}



