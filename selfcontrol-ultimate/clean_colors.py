import re
import os

target = "app/src/main/java/com/jo/selfcontrol/ultimate/MainActivity.kt"

with open(target, 'r', encoding='utf-8') as f:
    content = f.read()

# Modify roundedBackground to add stroke if color is WHITE
rounded_bg_orig = """    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 12f * resources.displayMetrics.density
        }
    }"""
rounded_bg_new = """    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            if (color == Color.WHITE) {
                setStroke((2f * resources.displayMetrics.density).toInt(), Color.BLACK)
            }
            cornerRadius = 12f * resources.displayMetrics.density
        }
    }"""
content = content.replace(rounded_bg_orig, rounded_bg_new)

# List of color replacements
# Dark backgrounds to WHITE (so they get a border and text inside can be black)
content = re.sub(r'Color\.parseColor\("#222222"\)', 'Color.WHITE', content)
content = re.sub(r'Color\.parseColor\("#1A1A2E"\)', 'Color.WHITE', content)

# Buttons and headers that were dark grey to BLACK
content = re.sub(r'Color\.parseColor\("#333333"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#424242"\)', 'Color.BLACK', content)

# Red colors to standard RED
content = re.sub(r'Color\.parseColor\("#FF5252"\)', 'Color.RED', content)
content = re.sub(r'Color\.parseColor\("#D32F2F"\)', 'Color.RED', content)
content = re.sub(r'Color\.parseColor\("#5C1E1E"\)', 'Color.RED', content)

# Other random colors to BLACK
content = re.sub(r'Color\.parseColor\("#BB86FC"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#2F3BFF"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#FFCA28"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#4CAF50"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#FFB74D"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#80CBC4"\)', 'Color.BLACK', content)

# Text colors that were grey/light should be BLACK
content = re.sub(r'Color\.parseColor\("#888888"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#AAAAAA"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#CCCCCC"\)', 'Color.BLACK', content)
content = re.sub(r'Color\.parseColor\("#DDDDDD"\)', 'Color.BLACK', content)

# Text that was WHITE inside rows that are now WHITE should be BLACK
content = re.sub(r'setTextColor\(Color\.WHITE\)', 'setTextColor(Color.BLACK)', content)

# But wait! Buttons that have a BLACK background should have WHITE text.
# The script above would turn all setTextColor(Color.WHITE) to BLACK.
# Let's fix that by searching for `background = roundedBackground(Color.BLACK)` 
# and ensuring its button text is WHITE, but since that's hard, 
# I will NOT do the blanket setTextColor(Color.WHITE) -> BLACK.
# Instead, I'll do a simpler strategy for app list items:
