package xyz.aezdek.daybook.core

/** One exam paper of a Cambridge International A Level syllabus. */
data class Paper(val id: String, val name: String)

/**
 * The three A Level subjects the planner is built around. [id] is what goes over the wire and into
 * storage, so it must never change once released.
 */
enum class Subject(
    val id: String,
    val title: String,
    val shortTitle: String,
    val syllabus: String,
    val papers: List<Paper>,
) {
    CS(
        id = "cs",
        title = "Computer Science",
        shortTitle = "CS",
        syllabus = "9618",
        papers = listOf(
            Paper("P1", "Theory Fundamentals"),
            Paper("P2", "Problem-solving and Programming"),
            Paper("P3", "Advanced Theory"),
            Paper("P4", "Practical"),
        ),
    ),
    MATHS(
        id = "maths",
        title = "Mathematics",
        shortTitle = "Maths",
        syllabus = "9709",
        papers = listOf(
            Paper("P1", "Pure Mathematics 1"),
            Paper("P2", "Pure Mathematics 2"),
            Paper("P3", "Pure Mathematics 3"),
            Paper("P4", "Mechanics"),
            Paper("P5", "Probability and Statistics 1"),
            Paper("P6", "Probability and Statistics 2"),
        ),
    ),
    PHYSICS(
        id = "physics",
        title = "Physics",
        shortTitle = "Physics",
        syllabus = "9702",
        papers = listOf(
            Paper("P1", "Multiple Choice"),
            Paper("P2", "AS Structured Questions"),
            Paper("P3", "Advanced Practical Skills"),
            Paper("P4", "A Level Structured Questions"),
            Paper("P5", "Planning, Analysis and Evaluation"),
        ),
    ),
    ;

    fun paper(id: String): Paper? = papers.firstOrNull { it.id == id }

    companion object {
        fun fromId(id: String): Subject? = entries.firstOrNull { it.id == id }
    }
}
