package lesson2

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

// Типы предметов
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
}

// Создание готовых предметов
val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    type = ItemType.POTION,
    12
)

val WOOD_SWORD = Item(
    "wood_sword",
    "Wood Sword",
    type = ItemType.WEAPON,
    1
)

fun putIntoSlot(
    slots: List<ItemStack?>,
    slotsIndex: Int,
    item: Item,
    addCount: Int
): List<ItemStack?>{
    // Возвращаем измененный список, но уже с новым предметом
    val newSlots = slots.toMutableList() // делаем копию списка слотов для его редактирования
    val current = newSlots[slotsIndex]   // Текущий стак в слоте, может быть null

    if (current == null){
        // Если слот куда хотим положить - пуст, создаем в нем новый стак
        val count = minOf(addCount, item.maxStack)
        newSlots[slotsIndex] = ItemStack(item, count)
        return newSlots
    }

    // Если слот в который кладем - не пуст, стакаем предметы если они того же типа
    if (current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        // Отнимаем от количества уже лежащих в стаке предметов от максимально допустимого количества в стаке
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotsIndex] = ItemStack(item, current.count + toAdd)
        return newSlots
    }
    return newSlots
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

fun main() = KoolApplication{
    val game = GameState()

    // Сцена UI
    addScene {
        defaultOrbitCamera()

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
            }
        }
    }
}