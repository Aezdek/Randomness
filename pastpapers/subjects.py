"""Cambridge IGCSE syllabus codes and the names used for their folders."""

SUBJECT_NAMES = {
    "0400": "Art and Design",
    "0410": "Music",
    "0417": "ICT",
    "0445": "Design and Technology",
    "0448": "Pakistan Studies",
    "0450": "Business Studies",
    "0452": "Accounting",
    "0455": "Economics",
    "0457": "Global Perspectives",
    "0460": "Geography",
    "0470": "History",
    "0471": "Travel and Tourism",
    "0475": "Literature in English",
    "0478": "Computer Science",
    "0493": "Islamiyat",
    "0495": "Sociology",
    "0500": "English (First Language)",
    "0510": "English Language",
    "0511": "English as a Second Language",
    "0520": "French",
    "0525": "German",
    "0530": "Spanish",
    "0539": "Urdu",
    "0580": "Mathematics",
    "0606": "Additional Mathematics",
    "0607": "International Mathematics",
    "0610": "Biology",
    "0620": "Chemistry",
    "0625": "Physics",
    "0653": "Combined Science",
    "0654": "Co-ordinated Sciences",
    "0680": "Environmental Management",
}

# Paper numbers to look for when downloading. Variants (41, 42, 43...) are added
# automatically, so only the paper number goes here.
DEFAULT_PAPERS = {
    "0478": [1, 2],
    "0510": [1, 2, 3, 4],
    "0580": [1, 2, 3, 4],
    "0610": [1, 2, 3, 4, 5, 6],
    "0620": [1, 2, 3, 4, 5, 6],
    "0625": [1, 2, 3, 4, 5, 6],
}

FALLBACK_PAPERS = [1, 2, 3, 4, 5, 6]
