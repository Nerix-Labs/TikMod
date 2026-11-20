package com.mts.tikmod.focus

import com.mts.tikmod.R

/**
 * Topics the "Stop the addiction" mode can let through. Each one is a list of words (hashtags without '#',
 * caption words) in the languages TikMod is translated to. A post matches when its caption or one of its
 * hashtags contains one of them; see [Focus.matches] for how words are compared.
 */
object FocusTopics {
    class Topic(val id: String, val label: Int, val emoji: String, val words: List<String>)

    val ALL: List<Topic> = listOf(
        Topic("learning", R.string.focus_topic_learning, "🎓", listOf(
            "learn", "learning", "learnontiktok", "edutok", "education", "educational", "tutorial", "howto", "how to",
            "lesson", "lessons", "study", "studytok", "studying", "explained", "explainer", "didyouknow", "did you know",
            "facts", "funfact", "funfacts", "knowledge", "teacher", "course", "lecture", "skills",
            "לימוד", "ללמוד", "למידה", "שיעור", "הסבר", "ידע", "עובדות", "עובדה", "מורה", "קורס",
            "تعلم", "تعليم", "درس", "معلومات", "aprender", "aprendeentiktok", "educación", "clase", "lección",
            "apprendre", "éducation", "cours", "обучение", "учеба", "урок", "знания", "lernen", "bildung", "wissen",
            "aula", "educação", "conhecimento", "öğren", "eğitim", "ders", "bilgi", "学习", "知识", "教程"
        )),
        Topic("science", R.string.focus_topic_science, "🔬", listOf(
            "science", "sciencetok", "physics", "chemistry", "biology", "astronomy", "space", "nasa", "experiment",
            "scientist", "math", "maths", "mathematics", "engineering", "universe",
            "מדע", "פיזיקה", "כימיה", "ביולוגיה", "אסטרונומיה", "חלל", "מתמטיקה", "ניסוי", "הנדסה",
            "علوم", "فيزياء", "كيمياء", "رياضيات", "ciencia", "física", "química", "matemáticas", "physique", "chimie",
            "mathématiques", "наука", "физика", "химия", "математика", "wissenschaft", "physik", "chemie", "mathe",
            "ciência", "matemática", "bilim", "fizik", "kimya", "matematik", "科学", "物理", "数学"
        )),
        Topic("tech", R.string.focus_topic_tech, "💻", listOf(
            "technology", "tech", "techtok", "coding", "programming", "programmer", "developer", "python", "javascript",
            "ai", "artificialintelligence", "computer", "software", "gadgets", "robotics", "linux", "android",
            "טכנולוגיה", "תכנות", "מתכנת", "מחשב", "מחשבים", "בינה מלאכותית", "תוכנה",
            "تقنية", "برمجة", "tecnología", "programación", "technologie", "programmation", "технологии",
            "программирование", "technik", "programmieren", "tecnologia", "programação", "teknoloji", "yazılım",
            "科技", "编程"
        )),
        Topic("languages", R.string.focus_topic_languages, "🗣️", listOf(
            "language", "languages", "english", "learnenglish", "spanish", "french", "german", "vocabulary", "grammar",
            "pronunciation", "polyglot", "duolingo",
            "אנגלית", "שפה", "שפות", "דקדוק", "אוצר מילים", "انجليزي", "لغة", "لغات", "idiomas", "inglés",
            "langues", "anglais", "английский", "язык", "sprachen", "englisch", "inglês", "ingilizce", "dil", "英语", "语言"
        )),
        Topic("history", R.string.focus_topic_history, "🏛️", listOf(
            "history", "historytok", "historical", "ancient", "archaeology", "museum",
            "היסטוריה", "ארכיאולוגיה", "מוזיאון", "تاريخ", "historia", "histoire", "история", "geschichte",
            "história", "tarih", "历史"
        )),
        Topic("health", R.string.focus_topic_health, "💪", listOf(
            "fitness", "workout", "exercise", "gym", "yoga", "health", "nutrition", "healthy", "running", "stretching",
            "meditation", "mentalhealth", "mindfulness",
            "כושר", "אימון", "בריאות", "תזונה", "יוגה", "מדיטציה", "ריצה",
            "رياضة", "صحة", "salud", "ejercicio", "santé", "здоровье", "фитнес", "gesundheit", "saúde", "treino",
            "sağlık", "spor", "健康", "健身"
        )),
        Topic("cooking", R.string.focus_topic_cooking, "🍳", listOf(
            "cooking", "recipe", "recipes", "baking", "chef", "kitchen", "cook", "foodtok",
            "מתכון", "מתכונים", "בישול", "אפייה", "מטבח", "طبخ", "وصفة", "receta", "cocina", "recette", "cuisine",
            "рецепт", "кулинария", "rezept", "kochen", "receita", "culinária", "tarif", "yemek", "食谱", "烹饪"
        )),
        Topic("money", R.string.focus_topic_money, "📈", listOf(
            "finance", "investing", "economics", "stocks", "budget", "personalfinance", "business", "entrepreneur",
            "money tips", "financialliteracy",
            "כלכלה", "השקעות", "חיסכון", "עסקים", "יזמות", "مال", "استثمار", "finanzas", "inversión", "argent",
            "финансы", "инвестиции", "finanzen", "finanças", "investimento", "finans", "yatırım", "理财", "投资"
        )),
        Topic("art", R.string.focus_topic_art, "🎨", listOf(
            "art", "artist", "drawing", "painting", "diy", "crafts", "design", "photography", "sketch",
            "אמנות", "ציור", "יצירה", "עיצוב", "צילום", "فن", "رسم", "arte", "dibujo", "dessin", "искусство",
            "рисование", "kunst", "zeichnen", "desenho", "sanat", "çizim", "艺术", "绘画"
        )),
        Topic("nature", R.string.focus_topic_nature, "🌿", listOf(
            "nature", "animals", "wildlife", "ocean", "travel", "documentary", "plants", "garden", "gardening", "environment",
            "טבע", "בעלי חיים", "חיות", "צמחים", "גינה", "טיול", "תיעודי", "طبيعة", "حيوانات", "naturaleza", "animales",
            "animaux", "природа", "животные", "natur", "tiere", "natureza", "animais", "doğa", "hayvanlar", "自然", "动物"
        )),
        Topic("books", R.string.focus_topic_books, "📚", listOf(
            "books", "booktok", "reading", "novel", "author", "literature",
            "ספרים", "ספר", "קריאה", "ספרות", "كتب", "قراءة", "libros", "lectura", "livres", "lecture", "книги", "чтение",
            "bücher", "lesen", "livros", "leitura", "kitap", "okuma", "读书", "书籍"
        ))
    )

    fun byId(id: String): Topic? = ALL.firstOrNull { it.id == id }
}
