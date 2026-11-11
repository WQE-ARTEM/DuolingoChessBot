import re

with open("app/src/main/java/com/chessbot/assistant/ChessOverlayService.kt", "r") as f:
    code = f.read()

# Добавим переменные памяти и метод правки
state_vars = """
    private var lockedFenState: String? = null
    private var editModeActive = false
"""

if "lockedFenState" not in code:
    code = code.replace("private var autoPlayEnabled = false", "private var autoPlayEnabled = false\n" + state_vars)

# Добавим кнопку "✏️ Правка" в панель управления
old_row2 = """val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }"""
new_row2 = """val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
           val btnEdit = createCompactButton("✏️ Правка").apply {
               setOnClickListener {
                   editModeActive = !editModeActive
                   text = if (editModeActive) "✏️ Режим правки: ВКЛ" else "✏️ Правка"
                   setBackgroundColor(if (editModeActive) Color.rgb(200, 120, 30) else Color.rgb(55, 70, 80))
               }
           }
           row2.addView(btnEdit)"""

if "✏️ Правка" not in code:
    code = code.replace(old_row2, new_row2)

with open("app/src/main/java/com/chessbot/assistant/ChessOverlayService.kt", "w") as f:
    f.write(code)
print("Service patched successfully!")
