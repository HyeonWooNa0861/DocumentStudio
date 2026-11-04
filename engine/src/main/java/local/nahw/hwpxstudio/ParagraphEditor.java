package local.nahw.hwpxstudio;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ParagraphEditor {
    private ParagraphEditor() {}

    static Inspection inspect(HwpxArchive archive) throws EngineException {
        LinkedHashMap<String, Block> byId = new LinkedHashMap<String, Block>();
        LinkedHashMap<String, Document> documents = new LinkedHashMap<String, Document>();
        for (String sectionName : archive.sectionNames()) {
            Document document = archive.parse(sectionName);
            documents.put(sectionName, document);
            addSectionBlocks(sectionName, document, byId);
        }
        return new Inspection(archive, documents, byId);
    }

    private static void addSectionBlocks(String sectionPath, Document document,
                                         LinkedHashMap<String, Block> byId) throws EngineException {
        String sectionName = sectionPath.substring(sectionPath.lastIndexOf('/') + 1);
        List<Element> all = descendants(document.getDocumentElement(), null);
        IdentityHashMap<Element, Integer> tableNumbers = new IdentityHashMap<Element, Integer>();
        int tableNumber = 0;
        for (Element element : all) {
            if (is(element, "tbl")) tableNumbers.put(element, ++tableNumber);
        }

        int paragraphOrdinal = 0;
        int plainParagraphNumber = 0;
        for (Element paragraph : all) {
            if (!is(paragraph, "p")) continue;
            String id = sectionName + ":p:" + paragraphOrdinal++;
            Element table = nearestAncestor(paragraph, "tbl");
            String label;
            if (table == null) {
                label = "문단 " + (++plainParagraphNumber);
            } else {
                Element row = nearestAncestorBefore(paragraph, "tr", table);
                Element cell = nearestAncestorBefore(paragraph, "tc", table);
                int rowNumber = row == null ? 1 : directElementIndex(row, "tr", table) + 1;
                int columnNumber = cell == null || row == null ? 1 : directChildIndex(row, cell, "tc") + 1;
                int paragraphNumber = cell == null ? 1 : paragraphIndexInCell(cell, paragraph) + 1;
                label = "표 " + tableNumbers.get(table) + " · " + rowNumber + "행 "
                        + columnNumber + "열 · 문단 " + paragraphNumber;
            }
            Analysis analysis = analyze(paragraph);
            String readOnlyReason = readOnlyContextReason(paragraph);
            if (readOnlyReason != null) analysis = Analysis.locked(paragraph, readOnlyReason);
            Block block = new Block(id, label, analysis.text, analysis.editable, analysis.reason,
                    sectionPath, paragraph, analysis.runElements);
            byId.put(id, block);
        }
    }

    static String readOnlyContextReason(Element element) {
        Node node = element.getParentNode();
        while (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
            Element ancestor = (Element) node;
            if (is(ancestor, "header") || is(ancestor, "footer")) {
                return "머리말·꼬리말은 현재 읽기 전용입니다.";
            }
            if (is(ancestor, "drawText") || is(ancestor, "rect") || is(ancestor, "line")) {
                return "이 개체의 글은 현재 읽기 전용입니다.";
            }
            node = node.getParentNode();
        }
        return null;
    }

    static void apply(Inspection inspection, Map<String, String> edits) throws EngineException {
        IdentityHashMap<Document, Boolean> changed = new IdentityHashMap<Document, Boolean>();
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            Block block = inspection.byId.get(edit.getKey());
            if (block == null) throw new EngineException("문서에서 수정 항목을 찾을 수 없습니다: " + edit.getKey());
            if (!block.editable) {
                throw new EngineException("수정할 수 없는 항목입니다: " + block.label + " (" + block.reason + ")");
            }
            String normalized = normalizeAndValidate(edit.getValue());
            if (replaceText(block, normalized)) {
                changed.put(block.paragraph.getOwnerDocument(), Boolean.TRUE);
            }
        }
        for (Map.Entry<String, Document> entry : inspection.documents.entrySet()) {
            if (changed.containsKey(entry.getValue())) inspection.archive.replaceXml(entry.getKey(), entry.getValue());
        }
    }

    /** Apply ordered structural edits while retaining stable IDs for this request. */
    static void applyStructure(Inspection inspection, List<?> operations) throws EngineException {
        java.util.HashSet<String> usedIds = new java.util.HashSet<String>(inspection.byId.keySet());
        long nextXmlId = 1;
        for (Document document : inspection.documents.values()) {
            for (Element paragraph : descendants(document.getDocumentElement(), "p")) {
                try { nextXmlId = Math.max(nextXmlId, Long.parseLong(paragraph.getAttribute("id")) + 1); }
                catch (NumberFormatException ignored) { }
            }
        }
        for (Object value : operations) {
            if (!(value instanceof Map)) throw new EngineException("문단 작업은 객체여야 합니다.");
            Map<?, ?> operation = (Map<?, ?>) value;
            Object action = operation.get("action");
            Object anchorId = operation.get("id");
            Block anchor = inspection.byId.get(anchorId);
            if (anchor == null || !anchor.editable) throw new EngineException("편집 가능한 문단을 선택해 주세요.");
            Node parent = anchor.paragraph.getParentNode();
            if (!(parent instanceof Element) || !(is((Element) parent, "sec") || is((Element) parent, "subList"))) {
                throw new EngineException("이 위치에서는 문단 구조를 바꿀 수 없습니다.");
            }
            if ("delete".equals(action)) {
                int count = 0;
                for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element && is((Element) n, "p")) count++;
                }
                if (count <= 1) throw new EngineException("구역 또는 표 셀에는 문단이 하나 이상 필요합니다.");
                parent.removeChild(anchor.paragraph);
                inspection.byId.remove(anchorId);
            } else if ("insertBefore".equals(action) || "insertAfter".equals(action)) {
                Object newIdValue = operation.get("newId");
                if (!(newIdValue instanceof String) || !((String) newIdValue).matches("new:[A-Za-z0-9-]{1,80}")
                        || !usedIds.add((String) newIdValue)) throw new EngineException("새 문단 ID가 올바르지 않습니다.");
                Element paragraph = (Element) anchor.paragraph.cloneNode(true);
                paragraph.setAttribute("id", Long.toString(nextXmlId++));
                paragraph.setAttribute("pageBreak", "0");
                paragraph.setAttribute("columnBreak", "0");
                paragraph.setAttribute("merged", "0");
                invalidateLineSegments(paragraph);
                List<Element> runs = new ArrayList<Element>();
                for (Node n = paragraph.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element && is((Element) n, "run")) runs.add((Element) n);
                }
                for (Element run : runs) writeRunText(run, "");
                parent.insertBefore(paragraph, "insertBefore".equals(action) ? anchor.paragraph : anchor.paragraph.getNextSibling());
                String newId = (String) newIdValue;
                inspection.byId.put(newId, new Block(newId, "새 문단", "", true, null, anchor.sectionPath, paragraph, runs));
            } else throw new EngineException("지원하지 않는 문단 작업입니다.");
            if (((Element) parent).hasAttribute("textHeight")) ((Element) parent).setAttribute("textHeight", "0");
            invalidateDescendantLineSegments((Element) parent);
        }
        if (!operations.isEmpty()) {
            for (Map.Entry<String, Document> entry : inspection.documents.entrySet()) {
                inspection.archive.replaceXml(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean replaceText(Block block, String text) throws EngineException {
        if (text.equals(block.text)) return false;
        String[] distributed;
        if (block.runElements.size() == 1) {
            distributed = new String[] { text };
        } else {
            distributed = distributeRuns(block, text);
        }
        for (int i = 0; i < block.runElements.size(); i++) {
            writeRunText(block.runElements.get(i), distributed[i]);
        }
        invalidateLineSegments(block.paragraph);
        return true;
    }

    private static String[] distributeRuns(Block block, String replacement) throws EngineException {
        int[] oldCodePoints = block.text.codePoints().toArray();
        int[] newCodePoints = replacement.codePoints().toArray();
        int[] oldStyles = originalRunStyles(block.runElements, oldCodePoints.length);
        StringBuilder[] output = new StringBuilder[block.runElements.size()];
        for (int i = 0; i < output.length; i++) output[i] = new StringBuilder();

        int prefix = 0;
        while (prefix < oldCodePoints.length && prefix < newCodePoints.length
                && oldCodePoints[prefix] == newCodePoints[prefix]) {
            appendStyled(output, oldStyles[prefix], newCodePoints[prefix]);
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldCodePoints.length - prefix && suffix < newCodePoints.length - prefix
                && oldCodePoints[oldCodePoints.length - 1 - suffix]
                == newCodePoints[newCodePoints.length - 1 - suffix]) {
            suffix++;
        }

        int oldInterior = oldCodePoints.length - prefix - suffix;
        int newInterior = newCodePoints.length - prefix - suffix;
        long dpRows = (long) oldInterior + 1L;
        long dpColumns = (long) newInterior + 1L;
        if (dpRows > 1_000_000L / dpColumns) {
            throw new EngineException("서식이 섞인 문단의 변경 범위가 너무 큽니다. 변경을 더 작은 단계로 나누어 주세요.");
        }
        distributeInterior(oldCodePoints, newCodePoints, oldStyles, output,
                prefix, oldInterior, newInterior);
        for (int i = suffix; i > 0; i--) {
            int oldIndex = oldCodePoints.length - i;
            int newIndex = newCodePoints.length - i;
            appendStyled(output, oldStyles[oldIndex], newCodePoints[newIndex]);
        }

        String[] result = new String[output.length];
        for (int i = 0; i < output.length; i++) result[i] = output[i].toString();
        return result;
    }

    private static void distributeInterior(int[] oldCodePoints, int[] newCodePoints, int[] oldStyles,
            StringBuilder[] output, int offset, int oldLength, int newLength) {
        int columns = newLength + 1;
        int[] lcs = new int[(oldLength + 1) * columns];
        for (int i = oldLength - 1; i >= 0; i--) {
            for (int j = newLength - 1; j >= 0; j--) {
                int index = i * columns + j;
                if (oldCodePoints[offset + i] == newCodePoints[offset + j]) {
                    lcs[index] = 1 + lcs[(i + 1) * columns + j + 1];
                } else {
                    lcs[index] = Math.max(lcs[(i + 1) * columns + j], lcs[index + 1]);
                }
            }
        }

        int oldIndex = 0, newIndex = 0;
        while (oldIndex < oldLength || newIndex < newLength) {
            if (oldIndex < oldLength && newIndex < newLength
                    && oldCodePoints[offset + oldIndex] == newCodePoints[offset + newIndex]) {
                appendStyled(output, oldStyles[offset + oldIndex], newCodePoints[offset + newIndex]);
                oldIndex++;
                newIndex++;
                continue;
            }
            int removedStart = oldIndex, insertedStart = newIndex;
            while (oldIndex < oldLength || newIndex < newLength) {
                if (oldIndex < oldLength && newIndex < newLength
                        && oldCodePoints[offset + oldIndex] == newCodePoints[offset + newIndex]) break;
                int deleteScore = oldIndex < oldLength
                        ? lcs[(oldIndex + 1) * columns + newIndex] : -1;
                int insertScore = newIndex < newLength
                        ? lcs[oldIndex * columns + newIndex + 1] : -1;
                if (oldIndex < oldLength && deleteScore >= insertScore) oldIndex++;
                else newIndex++;
            }
            int style = insertedStyle(oldStyles, offset + removedStart,
                    oldIndex - removedStart);
            for (int j = insertedStart; j < newIndex; j++) {
                appendStyled(output, style, newCodePoints[offset + j]);
            }
        }
    }

    private static int insertedStyle(int[] oldStyles, int oldIndex, int removed) {
        if (removed > 0) return oldStyles[oldIndex];
        if (oldIndex > 0) return oldStyles[oldIndex - 1];
        if (oldIndex < oldStyles.length) return oldStyles[oldIndex];
        return 0;
    }

    private static int[] originalRunStyles(List<Element> runs, int expectedLength) {
        int[] styles = new int[expectedLength];
        int position = 0;
        for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
            String text = runText(runs.get(runIndex));
            int count = text.codePointCount(0, text.length());
            for (int i = 0; i < count; i++) styles[position++] = runIndex;
        }
        return styles;
    }

    private static void appendStyled(StringBuilder[] output, int style, int codePoint) {
        output[Math.max(0, Math.min(style, output.length - 1))].appendCodePoint(codePoint);
    }

    private static void writeRunText(Element run, String text) {
        ArrayList<Element> textElements = new ArrayList<Element>();
        NodeList children = run.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && is((Element) child, "t")) {
                textElements.add((Element) child);
            }
        }
        if (textElements.isEmpty()) {
            String prefix = run.getPrefix();
            String qualified = prefix == null || prefix.length() == 0 ? "t" : prefix + ":t";
            textElements.add(run.getOwnerDocument().createElementNS(run.getNamespaceURI(), qualified));
            run.appendChild(textElements.get(0));
        }
        for (Element element : textElements) while (element.hasChildNodes()) {
            element.removeChild(element.getFirstChild());
        }
        appendText(textElements.get(0), text);
    }

    private static void appendText(Element textElement, String text) {
        String[] lines = text.split("\\n", -1);
        Document document = textElement.getOwnerDocument();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() > 0) textElement.appendChild(document.createTextNode(lines[i]));
            if (i + 1 < lines.length) {
                String prefix = textElement.getPrefix();
                String qualified = prefix == null || prefix.length() == 0
                        ? "lineBreak" : prefix + ":lineBreak";
                textElement.appendChild(document.createElementNS(textElement.getNamespaceURI(), qualified));
            }
        }
    }

    static void invalidateLineSegments(Element paragraph) {
        Element array = directChild(paragraph, "linesegarray");
        if (array != null) paragraph.removeChild(array);
    }

    static void invalidateDescendantLineSegments(Element container) {
        for (Element paragraph : descendants(container, "p")) {
            invalidateLineSegments(paragraph);
        }
    }

    private static Analysis analyze(Element paragraph) {
        ArrayList<Element> runs = new ArrayList<Element>();
        StringBuilder value = new StringBuilder();
        NodeList pChildren = paragraph.getChildNodes();
        for (int i = 0; i < pChildren.getLength(); i++) {
            Node node = pChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) node;
            if (is(element, "run")) {
                runs.add(element);
            } else if (!is(element, "linesegarray")) {
                return Analysis.locked(paragraph, "문단에 지원하지 않는 복합 요소가 있습니다.");
            }
        }
        if (runs.isEmpty()) return Analysis.locked(paragraph, "텍스트 실행 요소가 없는 문단입니다.");

        for (Element run : runs) {
            NodeList children = run.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element element = (Element) node;
                if (!is(element, "t")) {
                    return Analysis.locked(paragraph, "표·그림·필드 또는 구역 설정을 포함한 복합 문단입니다.");
                }
                NodeList textChildren = element.getChildNodes();
                for (int j = 0; j < textChildren.getLength(); j++) {
                    Node textNode = textChildren.item(j);
                    if (textNode.getNodeType() == Node.TEXT_NODE
                            || textNode.getNodeType() == Node.CDATA_SECTION_NODE) {
                        value.append(textNode.getNodeValue());
                    } else if (textNode.getNodeType() == Node.ELEMENT_NODE
                            && is((Element) textNode, "lineBreak")) {
                        value.append('\n');
                    } else if (textNode.getNodeType() == Node.ELEMENT_NODE) {
                        return Analysis.locked(paragraph, "탭·자동 번호 또는 특수 텍스트 요소를 포함한 문단입니다.");
                    }
                }
            }
        }
        return new Analysis(value.toString(), true, null, runs);
    }

    private static String runText(Element run) {
        StringBuilder value = new StringBuilder();
        NodeList children = run.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !is((Element) child, "t")) continue;
            NodeList textChildren = child.getChildNodes();
            for (int j = 0; j < textChildren.getLength(); j++) {
                Node text = textChildren.item(j);
                if (text.getNodeType() == Node.TEXT_NODE || text.getNodeType() == Node.CDATA_SECTION_NODE) {
                    value.append(text.getNodeValue());
                } else if (text.getNodeType() == Node.ELEMENT_NODE && is((Element) text, "lineBreak")) {
                    value.append('\n');
                }
            }
        }
        return value.toString();
    }

    private static String normalizeAndValidate(String text) throws EngineException {
        if (text == null) throw new EngineException("수정할 텍스트가 null일 수 없습니다.");
        if (text.length() > 100_000) throw new EngineException("한 문단의 텍스트는 100,000자를 넘을 수 없습니다.");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < normalized.length();) {
            int codePoint = normalized.codePointAt(i);
            if (!(codePoint == 0x9 || codePoint == 0xA
                    || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                    || (codePoint >= 0x10000 && codePoint <= 0x10FFFF))) {
                throw new EngineException("XML에 저장할 수 없는 제어 문자가 포함되어 있습니다.");
            }
            i += Character.charCount(codePoint);
        }
        return normalized;
    }

    private static String ownTextLenient(Element paragraph) {
        StringBuilder value = new StringBuilder();
        NodeList children = paragraph.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !is((Element) child, "run")) continue;
            NodeList runChildren = child.getChildNodes();
            for (int j = 0; j < runChildren.getLength(); j++) {
                Node runChild = runChildren.item(j);
                if (runChild.getNodeType() != Node.ELEMENT_NODE || !is((Element) runChild, "t")) continue;
                NodeList textChildren = runChild.getChildNodes();
                for (int k = 0; k < textChildren.getLength(); k++) {
                    Node textChild = textChildren.item(k);
                    if (textChild.getNodeType() == Node.TEXT_NODE || textChild.getNodeType() == Node.CDATA_SECTION_NODE) {
                        value.append(textChild.getNodeValue());
                    } else if (textChild.getNodeType() == Node.ELEMENT_NODE && is((Element) textChild, "lineBreak")) {
                        value.append('\n');
                    }
                }
            }
        }
        return value.toString();
    }

    private static List<Element> descendants(Element root, String localName) {
        ArrayList<Element> result = new ArrayList<Element>();
        collect(root, localName, result);
        return result;
    }

    private static void collect(Element element, String localName, List<Element> result) {
        if (localName == null || is(element, localName)) result.add(element);
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) collect((Element) child, localName, result);
        }
    }

    private static Element nearestAncestor(Element start, String localName) {
        Node node = start.getParentNode();
        while (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
            if (is((Element) node, localName)) return (Element) node;
            node = node.getParentNode();
        }
        return null;
    }

    private static Element nearestAncestorBefore(Element start, String localName, Element stopExclusive) {
        Node node = start.getParentNode();
        while (node != null && node != stopExclusive) {
            if (node.getNodeType() == Node.ELEMENT_NODE && is((Element) node, localName)) return (Element) node;
            node = node.getParentNode();
        }
        return null;
    }

    private static int directChildIndex(Element parent, Element target, String localName) {
        int index = 0;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && is((Element) child, localName)) {
                if (child == target) return index;
                index++;
            }
        }
        return 0;
    }

    private static int directElementIndex(Element row, String rowName, Element table) {
        return directChildIndex(table, row, rowName);
    }

    private static int paragraphIndexInCell(Element cell, Element target) {
        int index = 0;
        for (Element element : descendants(cell, "p")) {
            if (nearestAncestor(element, "tc") != cell) continue;
            if (element == target) return index;
            index++;
        }
        return 0;
    }

    private static Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && is((Element) child, localName)) return (Element) child;
        }
        return null;
    }

    private static boolean is(Element element, String localName) {
        String local = element.getLocalName();
        if (local == null) local = element.getNodeName().replaceFirst("^.*:", "");
        return localName.equalsIgnoreCase(local);
    }

    static final class Inspection {
        final HwpxArchive archive;
        final LinkedHashMap<String, Document> documents;
        final LinkedHashMap<String, Block> byId;

        Inspection(HwpxArchive archive, LinkedHashMap<String, Document> documents,
                   LinkedHashMap<String, Block> byId) {
            this.archive = archive;
            this.documents = documents;
            this.byId = byId;
        }

        List<Map<String, Object>> jsonBlocks() {
            ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (Block block : byId.values()) result.add(block.toJson());
            return result;
        }
    }

    private static final class Analysis {
        final String text;
        final boolean editable;
        final String reason;
        final List<Element> runElements;

        Analysis(String text, boolean editable, String reason, List<Element> runElements) {
            this.text = text;
            this.editable = editable;
            this.reason = reason;
            this.runElements = runElements;
        }

        static Analysis locked(Element paragraph, String reason) {
            return new Analysis(ownTextLenient(paragraph), false, reason, Collections.<Element>emptyList());
        }
    }

    static final class Block {
        final String id;
        final String label;
        final String text;
        final boolean editable;
        final String reason;
        final String sectionPath;
        final Element paragraph;
        final Element runElement;
        final List<Element> runElements;

        Block(String id, String label, String text, boolean editable, String reason,
              String sectionPath, Element paragraph, List<Element> runElements) {
            this.id = id;
            this.label = label;
            this.text = text;
            this.editable = editable;
            this.reason = reason;
            this.sectionPath = sectionPath;
            this.paragraph = paragraph;
            this.runElements = Collections.unmodifiableList(new ArrayList<Element>(runElements));
            this.runElement = runElements.isEmpty() ? null : runElements.get(0);
        }

        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put("label", label);
            map.put("text", text);
            map.put("editable", editable);
            map.put("reason", reason);
            return map;
        }
    }
}
