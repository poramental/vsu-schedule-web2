package com.vsuscheduleweb.parser;

import com.vsuscheduleweb.Exceptions.ParserException;
import com.vsuscheduleweb.entity.*;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Excel расписание → доменные сущности (Group/Subgroup/Lesson/Teacher).
 *
 * ⚠️ ВАЖНО:
 * 1) Этот парсер исторически "заточен" под конкретный шаблон Excel (границы, индексы колонок/строк).
 * 2) Мы сохраняем исходную работоспособность/логику (поведение), но делаем код устойчивее:
 *    - убираем опасные мутации Workbook/Cell,
 *    - добавляем защиту от выхода за границы,
 *    - исправляем баг с преподавателями (не добавлялись в список при множественных),
 *    - уменьшаем дубликаты преподавателей,
 *    - делаем потокобезопасность на уровне parse() (synchronized),
 *    - улучшаем читаемость и комментируем "почему так", а не "что делает строка".
 */
@Slf4j
@NoArgsConstructor
@Component
public class Parser {

    /**
     * Эти коллекции оставлены как "выходной результат" для совместимости с существующим кодом
     * (у тебя есть getters). Внутри parse() мы парсим в локальные структуры и в конце
     * атомарно заменяем ссылки, чтобы не оставлять объект в "полупромежуточном" состоянии.
     *
     * parse() сделан synchronized: Spring Component по умолчанию singleton, иначе есть race-condition.
     */
    private List<Teacher> teachers = new ArrayList<>();
    private List<Group> groups = new ArrayList<>();
    private List<Lesson> lessons = new ArrayList<>();

    private ParserStage parsMode;

    // ----------------------------
    // Константы "магии" — чтобы было понятно, что это за числа и где менять
    // ----------------------------

    /** Строки выше этой считаем "шапкой" и игнорируем (по исходной логике было < 12). */
    private static final int FIRST_DATA_ROW_INDEX = 12;

    /** Колонка, с которой начинается область расписания (по исходной логике постоянно сравнивалось с 3). */
    private static final int FIRST_SCHEDULE_COLUMN_INDEX = 3;

    /**
     * Порог границы, который используется как "разделитель секций".
     * В оригинале было >3 и иногда ==5.
     *
     * В Apache POI BorderStyle: THICK = 5, но тут сравнение делалось "числом".
     * Мы сохраняем логику и оставляем пороги константами.
     */
    private static final short BORDER_SECTION_SPLIT = 3;
    private static final short BORDER_THICK = 5;

    // ----------------------------
    // Public API (совместимость)
    // ----------------------------

    public List<Teacher> getTeachers() {
        // Геттеры не должны мутировать состояние.
        // Поэтому возвращаем уже очищенную структуру (мы чистим в parse()).
        return teachers;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public List<Lesson> getLessons() {
        return lessons;
    }

    // ----------------------------
    // Main entry
    // ----------------------------

    /**
     * Парсинг Excel файла.
     *
     * synchronized: Parser singleton, внутреннее состояние меняется.
     * Если хочешь масштабировать — лучше сделать парсер stateless и возвращать ParseResult,
     * но это может потребовать правок в остальном проекте.
     */
    public synchronized void parse(File xlsFile, String facult) throws ParserException {
        // Локальный контекст — безопаснее (не оставляем объект "полу-распарсенным" при исключениях)
        ParseContext ctx = new ParseContext();

        Workbook wb = readWorkbook(xlsFile);
        ctx.formatter = new DataFormatter(Locale.ROOT);
        ctx.evaluator = wb.getCreationHelper().createFormulaEvaluator();

        List<Cell> workspace = buildWorkspace(wb, ctx);
        if (workspace.isEmpty()) {
            throw new ParserException("workspace is empty: unexpected table format or empty sheet");
        }

        ctx.lengthOfWorkspace = getLengthOfWorkspace(workspace);

        // Стадии парсинга (как было)
        ctx.parsMode = ParserStage.PARSER_STAGE_NAME_OF_GROUPS;

        ctx.mapColumnToSubgroup = parseGroups(workspace, facult, ctx);
        // После parseGroups() стадия должна стать SKIP (как раньше), иначе дальше не пойдём корректно.
        // parseGroups() возвращает управление когда дошёл до границы.
        if (ctx.parsMode != ParserStage.PARSER_STAGE_SKIP) {
            // Нечастый случай, но лучше сообщить, чем молча сломаться.
            throw new ParserException("table format exception: groups section was not fully parsed");
        }

        parseLessonsTeachersAuditoriums(workspace, ctx);

        // Финальная очистка пустых "уроков" (сохраняем идею оригинала, но делаем один раз)
        cleanupEmptyLessons(ctx);

        // Публикуем результат атомарно
        this.groups = ctx.groups;
        this.teachers = ctx.teachers;
        this.lessons = ctx.lessons;
        this.parsMode = ctx.parsMode;
    }

    // ----------------------------
    // Workspace building (Excel -> List<Cell>)
    // ----------------------------

    /**
     * Собираем "рабочий набор" ячеек по критериям:
     * - либо есть левая граница (borderLeft > 0),
     * - либо колонка == 1 (по оригинальной логике).
     *
     * Далее фильтруем мусор:
     * - строки шапки,
     * - "курс", "№",
     * - пустые в первых колонках.
     */
    private List<Cell> buildWorkspace(Workbook wb, ParseContext ctx) {
        List<Cell> cells = getAllCellsWhereBorderLeftExist(wb);

        cells.removeIf(cell -> {
            String v = cellString(cell, ctx);
            // Исходные фильтры из твоего returnWorkspace()
            boolean headerWords = v.contains("курс") || v.contains("№");
            boolean emptyInFirstColumns = (cell.getColumnIndex() == 2 || cell.getColumnIndex() == 0 || cell.getColumnIndex() == 1) && v.isEmpty();
            boolean beforeDataRow = cell.getRowIndex() < FIRST_DATA_ROW_INDEX;
            return headerWords || emptyInFirstColumns || beforeDataRow;
        });

        return cells;
    }

    private List<Cell> getAllCellsWhereBorderLeftExist(Workbook wb) {
        Sheet st = wb.getSheetAt(0);
        List<Cell> cells = new ArrayList<>();

        st.forEach(row -> row.forEach(cell -> {
            // Никаких try/catch на IllegalStateException:
            // - не трогаем значение cell.setCellValue(...)
            // - тип ячейки читаем через DataFormatter позже
            if (cell.getCellStyle() != null && cell.getCellStyle().getBorderLeft() > 0) {
                cells.add(cell);
            }
            if (cell.getColumnIndex() == 1) {
                cells.add(cell);
            }
        }));

        return cells;
    }

    // ----------------------------
    // Groups parsing
    // ----------------------------

    /**
     * Парсит блок групп/ID/подгрупп.
     *
     * Возвращает map: columnIndex -> Subgroup
     *
     * Важное улучшение:
     * - защита от выхода за границы i+1
     * - явные условия остановки вместо for(true)
     */
    private HashMap<Integer, Subgroup> parseGroups(List<Cell> workspace, String facult, ParseContext ctx) throws ParserException {
        HashMap<Integer, Subgroup> map = new HashMap<>();

        Queue<Group> queue = new ArrayDeque<>();
        Queue<Group> queueCache = new ArrayDeque<>();
        int groupCount = 0;

        int i = 0;
        while (i < workspace.size()) {
            Cell cur = workspace.get(i);
            Cell next = safeGet(workspace, i + 1);

            if (ctx.parsMode == ParserStage.PARSER_STAGE_NAME_OF_GROUPS) {
                if (next == null) {
                    throw new ParserException("table format exception: unexpected end while reading group names");
                }

                String groupName = cellString(cur, ctx);
                String nextVal = cellString(next, ctx);

                if (nextVal.isEmpty()) {
                    throw new ParserException("table format exception: empty group header cell");
                }

                Group group = new Group().setName(groupName);

                // Исходная логика определения числа подгрупп (очень завязано на layout Excel)
                if (!(next.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX && borderLeft(next) == BORDER_THICK)) {
                    group.setCountOfSubGroups(next.getColumnIndex() - cur.getColumnIndex());
                } else {
                    Cell prev = safeGet(workspace, i - 1);
                    if (prev == null) {
                        throw new ParserException("table format exception: cannot infer subgroups count (missing previous cell)");
                    }
                    group.setCountOfSubGroups(cur.getColumnIndex() - prev.getColumnIndex());
                }

                ctx.groups.add(group);
                queue.add(group);
                groupCount++;

                // переход стадий как в оригинале
                if (next.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX && borderLeft(next) == BORDER_THICK) {
                    ctx.parsMode = ParserStage.PARSER_STAGE_GROUPS_ID;
                }

                i++;
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_GROUPS_ID) {
                Group group = queue.poll();
                if (group != null) {
                    queueCache.add(group);
                    // id = "что-то/факультет"
                    group.setId(cellString(cur, ctx) + "/" + facult);
                }

                if (next != null
                        && next.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX
                        && borderLeft(next) > BORDER_SECTION_SPLIT) {

                    // возвращаем группы обратно в очередь (как было)
                    for (int j = 0; j < groupCount; j++) {
                        Group g = queueCache.poll();
                        if (g != null) queue.add(g);
                    }
                    ctx.parsMode = ParserStage.PARSER_STAGE_SUBGROUPS;
                }

                i++;
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_SUBGROUPS) {
                Group group = queue.poll();
                int lastIndex = 0;

                if (group != null) {
                    for (int index = 0; index < group.getCountOfSubGroups(); index++) {
                        Cell c = safeGet(workspace, i + index);
                        if (c == null) {
                            throw new ParserException("table format exception: unexpected end while reading subgroups");
                        }
                        String subgroupId = cellString(c, ctx);
                        Subgroup subgroup = new Subgroup().setId(subgroupId);

                        map.put(c.getColumnIndex(), subgroup);
                        group.addSubgroup(subgroup);
                        lastIndex = index;
                    }
                }

                if (next != null && next.getColumnIndex() == 0 && borderLeft(next) > BORDER_SECTION_SPLIT) {
                    ctx.parsMode = ParserStage.PARSER_STAGE_SKIP;
                    return map;
                }

                // пропускаем уже обработанные колонки подгрупп
                i += (lastIndex + 1);
                continue;
            }

            // Если мы дошли сюда — формат неожиданный
            throw new ParserException("table format exception: unexpected parser stage while parsing groups");
        }

        throw new ParserException("table format exception: groups section not found/terminated");
    }

    // ----------------------------
    // Lessons / Teachers / Auditoriums parsing
    // ----------------------------

    private void parseLessonsTeachersAuditoriums(List<Cell> workspace, ParseContext ctx) throws ParserException {
        String day = "";
        String date = "";
        String startTime = "";
        String endTime = "";

        Queue<Lesson> queueOfLessons = new ArrayDeque<>();
        Queue<Lesson> queueCache = new ArrayDeque<>();
        int countOfLessons = 0;

        int skipBorders = 0;

        // Преподавателей лучше дедуплицировать: иначе будет тысячи одинаковых объектов
        Map<String, Teacher> teacherIndex = new HashMap<>();

        // (Опционально) ускоряем поиск группы по id подгруппы
        Map<String, Group> subgroupIdToGroup = indexSubgroupToGroup(ctx.groups);

        for (int i = 0; i < workspace.size() - 1; i++) {
            Cell cur = workspace.get(i);
            Cell next = safeGet(workspace, i + 1);

            if (ctx.parsMode == ParserStage.PARSER_STAGE_SKIP) {
                if (next != null && borderLeft(next) > BORDER_SECTION_SPLIT) {
                    skipBorders++;
                    if (skipBorders == 3) {
                        ctx.parsMode = ParserStage.PARSER_STAGE_DAY;
                        continue;
                    }
                }
                continue;
            }

            // В оригинале переход в TIME происходил при columnIndex==2 и borderLeft > 3.
            if (cur.getColumnIndex() == 2 && borderLeft(cur) > BORDER_SECTION_SPLIT) {
                ctx.parsMode = ParserStage.PARSER_STAGE_TIME;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_DAY) {
                day = cellString(cur, ctx);
                ctx.parsMode = ParserStage.PARSER_STAGE_DATE;
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_DATE) {
                date = cellString(cur, ctx);
                ctx.parsMode = ParserStage.PARSER_STAGE_TIME;
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_TIME) {
                // Время ожидается как строка, содержащая "... (HH.MM-HH.MM)" и т.п.
                // Мы сохраняем исходную стратегию split(), но безопаснее обрабатываем пустые/кривые ячейки.
                startTime = parseStartTime(cur, ctx);
                endTime = parseEndTime(cur, ctx);
                ctx.parsMode = ParserStage.PARSER_STAGE_LESSONS;
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_LESSONS) {
                Lesson lesson = parseLesson(cur, ctx);
                lesson.setDate(date)
                        .setWeekDay(day)
                        .setStartTime(startTime)
                        .setEndTime(endTime)
                        .setId(UUID.randomUUID());

                ctx.lessons.add(lesson);
                queueOfLessons.add(lesson);
                countOfLessons++;

                // Раскладка таблицы: если между колонками "прыжок", значит lesson общий на несколько подгрупп
                // (в оригинале метод назывался isAdjacentColumns, но логика была "gap >= 2" — оставляем, но комментируем).
                if (next != null && (isAdjacentColumns(cur, next)
                        || isPenultimateColumnAndTheNextIsTheFirst(cur, next, ctx.lengthOfWorkspace)
                        || isSingleColumnInTheRow(cur, next))) {

                    int firstColumnIndex = cur.getColumnIndex();
                    int secondColumnIndex = next.getColumnIndex();

                    // Частный костыль из оригинала: "3 и 3" интерпретируется как конец строки
                    if (firstColumnIndex == FIRST_SCHEDULE_COLUMN_INDEX && secondColumnIndex == FIRST_SCHEDULE_COLUMN_INDEX) {
                        secondColumnIndex = ctx.lengthOfWorkspace + FIRST_SCHEDULE_COLUMN_INDEX;
                    }

                    for (int col = firstColumnIndex; col < secondColumnIndex; col++) {
                        Subgroup sg = ctx.mapColumnToSubgroup.get(col);
                        if (sg == null) {
                            throw new ParserException("table format exception: subgroup not found for column " + col);
                        }

                        Group group = subgroupIdToGroup.get(sg.getId());
                        if (group == null) {
                            // fallback на старый O(n^2), если индекс не сработал (на всякий)
                            Optional<Group> opt = findGroupBySubgroupIndex(sg.getId(), ctx.groups);
                            if (opt.isPresent()) group = opt.get();
                        }
                        if (group == null) {
                            throw new ParserException("table format exception: group not found for subgroup " + sg.getId());
                        }

                        if (!isThisLessonInGroup(lesson, group)) {
                            group.addLesson(lesson);
                        }
                    }
                } else {
                    // Урок относится к конкретной подгруппе
                    Subgroup sg = ctx.mapColumnToSubgroup.get(cur.getColumnIndex());
                    if (sg == null) {
                        // В оригинале был NPE + println. Тут лучше дать сигнал формата.
                        log.warn("No subgroup mapping for column {} (cell='{}')", cur.getColumnIndex(), cellString(cur, ctx));
                    } else {
                        sg.addLesson(lesson);
                    }
                }

                // Переход к блоку преподавателей
                if (next != null && next.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX) {
                    ctx.parsMode = ParserStage.PARSER_STAGE_TEACHERS;
                }
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_TEACHERS) {
                Lesson lesson = queueOfLessons.poll();
                if (lesson != null) {
                    queueCache.add(lesson);

                    String teacherCell = cellString(cur, ctx);
                    if (!teacherCell.isEmpty()) {
                        if (!teacherCell.contains(",")) {
                            Teacher t = getOrCreateTeacher(teacherCell, teacherIndex);
                            t.addLesson(lesson);
                        } else {
                            for (String teacherString : splitManyTeachersToList(teacherCell)) {
                                if (teacherString.isBlank()) continue;
                                Teacher t = getOrCreateTeacher(teacherString, teacherIndex);
                                t.addLesson(lesson);
                            }
                        }
                    }
                }

                // Переход к аудиториям + восстановление очереди уроков
                if (next != null && next.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX) {
                    ctx.parsMode = ParserStage.PARSER_STAGE_AUDITORIUMS;

                    for (int j = 0; j < countOfLessons; j++) {
                        Lesson l = queueCache.poll();
                        if (l != null) queueOfLessons.add(l);
                        else break;
                    }
                }
                continue;
            }

            if (ctx.parsMode == ParserStage.PARSER_STAGE_AUDITORIUMS) {
                Lesson lesson = queueOfLessons.poll();

                if (lesson == null) {
                    // Конец блока аудитории: если следующая ячейка — "разделитель дня", возвращаемся к DAY
                    if (next != null && borderLeft(next) == BORDER_THICK && next.getColumnIndex() == 0) {
                        ctx.parsMode = ParserStage.PARSER_STAGE_DAY;
                    }
                    countOfLessons = 0;
                    continue;
                }

                lesson.setAuditorium(cellString(cur, ctx));
            }
        }

        // Добавляем всех уникальных преподавателей в ctx.teachers (в конце, чтобы список был чистый)
        ctx.teachers.addAll(teacherIndex.values());
    }

    // ----------------------------
    // Teacher parsing / indexing
    // ----------------------------

    /**
     * Получает существующего преподавателя или создаёт нового.
     * Ключ должен быть стабильным: "Фамилия|Инициалы|Квалификация".
     */
    private Teacher getOrCreateTeacher(String raw, Map<String, Teacher> index) {
        Teacher t = parseTeacher(raw);
        String key = teacherKey(t);
        return index.computeIfAbsent(key, k -> t);
    }

    private String teacherKey(Teacher t) {
        // trim + lower для унификации (иначе будут дубли из-за пробелов/регистра)
        return (safe(t.getLastname()) + "|" + safe(t.getInitials()) + "|" + safe(t.getQualification())).toLowerCase(Locale.ROOT).trim();
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private Teacher parseTeacher(String s) {
        s = s.trim();
        String teacherLastName = parseTeacherLastname(s);
        String teacherInitials = parseTeacherInitials(s);
        String teacherQualification = parseTeacherQualification(s);

        return new Teacher()
                .setLastname(teacherLastName)
                .setQualification(teacherQualification)
                .setInitials(teacherInitials);
    }

    private List<String> splitManyTeachersToList(String cellValue) {
        return Arrays.stream(cellValue.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private String[] splitTeacherStringToArr(String s) {
        // Убираем повторные пробелы — иначе arr[1] может упасть
        return Arrays.stream(s.trim().split("\\s+"))
                .filter(x -> !x.isBlank())
                .toArray(String[]::new);
    }

    private String parseTeacherLastname(String s) {
        String[] arr = splitTeacherStringToArr(s);
        return arr.length > 0 ? arr[0] : "";
    }

    private String parseTeacherQualification(String s) {
        String[] arr = splitTeacherStringToArr(s);
        if (arr.length == 0) return "";
        return arr[arr.length - 1].replace("(", "").replace(")", "");
    }

    private String parseTeacherInitials(String s) {
        String[] arr = splitTeacherStringToArr(s);
        return arr.length > 1 ? arr[1] : "";
    }

    // ----------------------------
    // Lesson parsing
    // ----------------------------

    private Lesson parseLesson(Cell cell, ParseContext ctx) {
        String lessonName = parseLessonName(cell, ctx);
        String lessonType = parseLessonType(cell, ctx);
        return new Lesson()
                .setName(lessonName)
                .setType(lessonType);
    }

    private String parseLessonName(Cell cell, ParseContext ctx) {
        String v = cellString(cell, ctx);

        // Убираем маркеры типа "(лк)" "(пз)" "(лаб)" — но не режем любые скобки в названии предмета.
        // Это менее разрушительно, чем оригинальная логика "всё со скобками выкинуть".
        String cleaned = v
                .replace("(лк)", "")
                .replace("(пз)", "")
                .replace("(лаб)", "");

        // нормализуем пробелы
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned;
    }

    private String parseLessonType(Cell cell, ParseContext ctx) {
        String v = cellString(cell, ctx);
        if (v.contains("(лк)")) return "лк";
        if (v.contains("(пз)")) return "пз";
        if (v.contains("(лаб)")) return "лаб";
        return "";
    }

    // ----------------------------
    // Time parsing
    // ----------------------------

    private String parseStartTime(Cell cell, ParseContext ctx) throws ParserException {
        String[] arr = splitTimeCellToArr(cell, ctx);
        if (arr.length == 0) throw new ParserException("time format exception: cannot parse start time");
        return arr[0].replace("(", "").replace(".", ":").trim();
    }

    private String parseEndTime(Cell cell, ParseContext ctx) throws ParserException {
        String[] arr = splitTimeCellToArr(cell, ctx);
        if (arr.length == 0) throw new ParserException("time format exception: cannot parse end time");
        return arr[arr.length - 1].replace(")", "").replace(".", ":").trim();
    }

    /**
     * Оригинальная логика:
     * - split по пробелам, убрать пустые
     * - взять последний токен и split по "-"
     *
     * Мы добавили:
     * - защиту от пустых значений
     * - нормализацию пробелов
     */
    private String[] splitTimeCellToArr(Cell cell, ParseContext ctx) throws ParserException {
        String v = cellString(cell, ctx).trim();
        if (v.isEmpty()) {
            throw new ParserException("time format exception: empty time cell");
        }

        List<String> arr = Arrays.stream(v.split("\\s+"))
                .filter(x -> !x.isBlank())
                .collect(Collectors.toList());

        if (arr.isEmpty()) {
            throw new ParserException("time format exception: cannot split time cell");
        }

        String last = arr.get(arr.size() - 1);
        String[] range = last.split("-");
        if (range.length == 0) {
            throw new ParserException("time format exception: cannot parse time range");
        }
        return range;
    }

    // ----------------------------
    // Workbook reading
    // ----------------------------

    public Workbook readWorkbook(File file) throws ParserException {
        try {
            return WorkbookFactory.create(file);
        } catch (Exception e) {
            throw new ParserException("cannot read workbook");
        }
    }

    // ----------------------------
    // Helpers: group lookup, dedup, cleanup
    // ----------------------------

    private Optional<Group> findGroupBySubgroupIndex(String index, List<Group> groups) {
        for (Group g : groups) {
            for (Subgroup sg : g.getSubgroups()) {
                if (Objects.equals(sg.getId(), index)) {
                    return Optional.of(g);
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Group> indexSubgroupToGroup(List<Group> groups) {
        Map<String, Group> map = new HashMap<>();
        for (Group g : groups) {
            for (Subgroup sg : g.getSubgroups()) {
                if (sg.getId() != null) map.put(sg.getId(), g);
            }
        }
        return map;
    }

    private boolean isThisLessonInGroup(Lesson lesson, Group group) {
        // Оставляем оригинальную семантику: совпали startTime + date => урок "общий", второй не добавляем.
        for (Lesson groupLesson : group.getCommonLessons()) {
            if (Objects.equals(groupLesson.getStartTime(), lesson.getStartTime())
                    && Objects.equals(groupLesson.getDate(), lesson.getDate())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Финальная очистка пустых уроков — один раз, не в геттерах.
     */
    private void cleanupEmptyLessons(ParseContext ctx) {
        // teachers lessons
        for (Teacher t : ctx.teachers) {
            if (t.getLessons() != null) {
                t.getLessons().removeIf(l -> l == null || safe(l.getName()).isEmpty());
            }
        }

        // groups lessons + subgroups lessons
        for (Group g : ctx.groups) {
            if (g.getCommonLessons() != null) {
                g.getCommonLessons().removeIf(l -> l == null || safe(l.getName()).isEmpty());
            }
            if (g.getSubgroups() != null) {
                for (Subgroup sg : g.getSubgroups()) {
                    if (sg.getLessons() != null) {
                        sg.getLessons().removeIf(l -> l == null || safe(l.getName()).isEmpty());
                    }
                }
            }
        }

        // global lessons
        ctx.lessons.removeIf(l -> l == null || safe(l.getName()).isEmpty());
    }

    // ----------------------------
    // Layout heuristics (оставлены, но прокомментированы)
    // ----------------------------

    private boolean isSingleColumnInTheRow(Cell cell, Cell nextCell) {
        return (nextCell.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX && cell.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX);
    }

    /**
     * Название из оригинала не отражает реальность:
     * тут считается, что между колонками есть "разрыв" (>=2), значит lesson относится к диапазону.
     * Оставили для совместимости поведения.
     */
    private boolean isAdjacentColumns(Cell cell, Cell nextCell) {
        return (nextCell.getColumnIndex() - cell.getColumnIndex()) >= 2;
    }

    private boolean isPenultimateColumnAndTheNextIsTheFirst(Cell cell, Cell nextCell, int lengthOfWorkSpace) {
        return ((cell.getColumnIndex() == lengthOfWorkSpace + 1) && nextCell.getColumnIndex() == FIRST_SCHEDULE_COLUMN_INDEX);
    }

    private int getLengthOfWorkspace(List<Cell> cells) throws ParserException {
        // Оригинальная логика: идём от i=1 до "границы" (borderLeft >= 3)
        // и потом делаем странную формулу (length+1)*2.
        // Сохраняем, но добавляем защиту.
        int length = 0;

        for (int i = 1; i < cells.size(); i++) {
            if (borderLeft(cells.get(i)) >= BORDER_SECTION_SPLIT) {
                break;
            }
            length++;
        }

        if (length == 0) {
            throw new ParserException("table format exception: cannot determine workspace length");
        }

        return (length + 1) * 2;
    }

    // ----------------------------
    // Low-level helpers
    // ----------------------------

    private Cell safeGet(List<Cell> list, int idx) {
        if (idx < 0 || idx >= list.size()) return null;
        return list.get(idx);
    }

    private short borderLeft(Cell cell) {
        if (cell == null || cell.getCellStyle() == null) return 0;
        return cell.getCellStyle().getBorderLeft();
    }

    /**
     * Унифицированное чтение значения ячейки:
     * - корректно для строк/чисел/формул
     * - не мутирует workbook/ячейку
     */
    private String cellString(Cell cell, ParseContext ctx) {
        if (cell == null) return "";
        try {
            String s = ctx.formatter.formatCellValue(cell, ctx.evaluator);
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            // Не валимся из-за одной кривой ячейки — лучше пустую строку и лог.
            log.debug("Failed to format cell [r={}, c={}]: {}", cell.getRowIndex(), cell.getColumnIndex(), e.getMessage());
            return "";
        }
    }

    // ----------------------------
    // Parse context (внутренний объект состояния)
    // ----------------------------

    private static class ParseContext {
        ParserStage parsMode;

        List<Teacher> teachers = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        List<Lesson> lessons = new ArrayList<>();

        HashMap<Integer, Subgroup> mapColumnToSubgroup = new HashMap<>();
        int lengthOfWorkspace = 0;

        DataFormatter formatter;
        FormulaEvaluator evaluator;
    }
}
