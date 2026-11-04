(function () {
  "use strict";

  const Core = window.StudioCore;
  const LIMITS = Core.LIMITS;
  const elements = {
    body: document.body,
    viewport: document.getElementById("viewport"),
    pages: document.getElementById("pages"),
    empty: document.getElementById("empty-state"),
    editor: document.getElementById("canonical-editor"),
    notice: document.getElementById("notice"),
    pageStatus: document.getElementById("page-status"),
    selectionStatus: document.getElementById("selection-status"),
    warningStatus: document.getElementById("warning-status"),
    busyStatus: document.getElementById("busy-status"),
    save: document.getElementById("save-button"),
    fallback: document.getElementById("fallback-button"),
    insertBefore: document.getElementById("insert-before"),
    insertAfter: document.getElementById("insert-after"),
    deleteParagraph: document.getElementById("delete-paragraph"),
    undo: document.getElementById("undo-button"),
    redo: document.getElementById("redo-button"),
    fontFamily: document.getElementById("font-family"),
    fontSize: document.getElementById("font-size"),
    tableWidth: document.getElementById("table-width"),
    tableHeight: document.getElementById("table-height"),
    zoomOut: document.getElementById("zoom-out"),
    zoomIn: document.getElementById("zoom-in"),
    zoomValue: document.getElementById("zoom-value")
  };

  const state = {
    model: null,
    viewModel: null,
    paragraphs: new Map(),
    tables: new Map(),
    locations: new Map(),
    baseline: { texts: {}, styles: {}, tables: {} },
    draft: { texts: {}, styles: {}, tables: {} },
    selectedParagraphId: null,
    selectedTableId: null,
    selectionStart: 0,
    selectionEnd: 0,
    composing: false,
    busy: false,
    dirty: false,
    zoom: 1,
    undo: [],
    redo: [],
    lastHistory: null,
    pages: [],
    fragments: {},
    anchorPages: new Map(),
    appliedPageBreaks: new Set(),
    repeatingRegions: [],
    runCache: new Map(),
    imageResources: new Map(),
    imageGeneration: 0,
    warnings: [],
    renderQueued: false,
    renderOperations: 0
  };

  function guardLayout(label, id) {
    state.renderOperations += 1;
    if (state.renderOperations > 5000) {
      throw new Error(`문서 배치가 안전 한도를 넘었습니다 (${label}${id ? `: ${id}` : ""}).`);
    }
  }

  function bridge(kind, payload) {
    const handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.studio;
    if (!handler || typeof handler.postMessage !== "function") return;
    const message = Object.assign({ kind }, payload || {});
    try { handler.postMessage(message); } catch (_) { /* native host may be closing */ }
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function number(value, fallback) {
    return typeof value === "number" && Number.isFinite(value) ? value : fallback;
  }

  function normalizedStyle(node) {
    const base = node && node.style && typeof node.style === "object" ? node.style : {};
    const draft = node && state.draft.styles[node.id] ? state.draft.styles[node.id] : {};
    return Object.assign({}, base, draft);
  }

  function paragraphText(node) {
    return Object.prototype.hasOwnProperty.call(state.draft.texts, node.id)
      ? state.draft.texts[node.id] : (typeof node.text === "string" ? node.text : "");
  }

  function normalizedTable(node) {
    const draft = state.draft.tables[node.id] || {};
    return {
      widthPt: number(draft.widthPt, number(node.widthPt, 360)),
      heightPt: number(draft.heightPt, number(node.heightPt, 1))
    };
  }

  function applyTextStyle(target, style) {
    if (!style) return;
    if (typeof style.fontFamily === "string" && style.fontFamily) target.style.fontFamily = JSON.stringify(style.fontFamily) + ", sans-serif";
    if (number(style.fontSizePt, 0) > 0) target.style.fontSize = `${style.fontSizePt * state.zoom}pt`;
    target.style.fontWeight = style.bold === true ? "700" : "400";
    target.style.fontStyle = style.italic === true ? "italic" : "normal";
    target.style.textDecoration = style.underline === true ? "underline" : "none";
    if (typeof style.color === "string" && /^#[0-9a-f]{6}$/iu.test(style.color)) target.style.color = style.color;
    if (["left", "right", "center", "justify"].includes(style.alignment)) target.style.textAlign = style.alignment;
    const lineHeightPt = number(style.lineHeightPt, 0);
    const lineHeight = number(style.lineHeight, 0);
    if (lineHeightPt > 0) target.style.lineHeight = `${lineHeightPt * state.zoom}pt`;
    else if (lineHeight > 0.5 && lineHeight < 5) target.style.lineHeight = String(lineHeight);
  }

  function warning(code, message, id) {
    const key = `${code}|${id || ""}|${message}`;
    if (state.warnings.some((item) => item.key === key)) return;
    state.warnings.push({ key, code, message, id: id || null });
  }

  function indexNode(node, location) {
    if (!node || typeof node !== "object") return;
    if (node.type === "container" || node.type === "shape") {
      for (const child of Array.isArray(node.children) ? node.children : []) indexNode(child, location);
      return;
    }
    if (node.type === "paragraph" && typeof node.id === "string") {
      state.paragraphs.set(node.id, node);
      state.locations.set(node.id, Object.assign({}, location, { label: node.label || node.id }));
      state.baseline.texts[node.id] = typeof node.text === "string" ? node.text : "";
      state.baseline.styles[node.id] = {
        fontFamily: normalizedInitialFont(node.style),
        fontSizePt: number(node.style && node.style.fontSizePt, 10),
        fontFamilyExplicit: false,
        fontSizePtExplicit: false
      };
      state.draft.texts[node.id] = state.baseline.texts[node.id];
      state.draft.styles[node.id] = Core.clone(state.baseline.styles[node.id]);
      return;
    }
    if (node.type === "table" && typeof node.id === "string") {
      state.tables.set(node.id, node);
      const dimensions = { widthPt: number(node.widthPt, 360), heightPt: number(node.heightPt, 1) };
      state.baseline.tables[node.id] = dimensions;
      state.draft.tables[node.id] = Core.clone(dimensions);
      for (const row of Array.isArray(node.rows) ? node.rows : []) {
        for (const cell of Array.isArray(row.cells) ? row.cells : []) {
          const childLocation = Object.assign({}, location, { tableId: node.id, cellId: cell.id || null });
          for (const child of Array.isArray(cell.children) ? cell.children : []) indexNode(child, childLocation);
        }
      }
    }
  }

  function normalizedInitialFont(style) {
    return style && typeof style.fontFamily === "string" && style.fontFamily ? style.fontFamily : "Apple SD Gothic Neo";
  }

  function rebuildIndex(model) {
    state.imageGeneration += 1;
    state.imageResources.clear();
    state.paragraphs.clear();
    state.tables.clear();
    state.locations.clear();
    state.runCache.clear();
    state.baseline = { texts: {}, styles: {}, tables: {} };
    state.draft = { texts: {}, styles: {}, tables: {} };
    model.sections.forEach((section, sectionIndex) => {
      const location = { sectionId: section.id || `section-${sectionIndex + 1}`, sectionIndex };
      for (const child of Array.isArray(section.children) ? section.children : []) indexNode(child, location);
    });
  }

  function makePage(section, sectionIndex, overheight) {
    guardLayout("page", section && section.id);
    if (state.pages.length >= 300) throw new Error("미리보기 페이지가 300쪽 안전 한도를 넘었습니다.");
    const geometry = section.page || {};
    const page = {
      widthPt: number(geometry.widthPt, 595),
      heightPt: number(geometry.heightPt, 842),
      marginTopPt: number(geometry.marginTopPt, 56) + number(geometry.marginHeaderPt, 0),
      marginRightPt: number(geometry.marginRightPt, 56),
      marginBottomPt: number(geometry.marginBottomPt, 56) + number(geometry.marginFooterPt, 0),
      marginLeftPt: number(geometry.marginLeftPt, 56),
      paperMarginTopPt: number(geometry.marginTopPt, 56),
      paperMarginBottomPt: number(geometry.marginBottomPt, 56),
      headerHeightPt: number(geometry.marginHeaderPt, number(geometry.marginTopPt, 56)),
      footerHeightPt: number(geometry.marginFooterPt, number(geometry.marginBottomPt, 56))
    };
    const wrap = element("section", "page-wrap");
    const sheet = element("article", overheight ? "page overheight" : "page");
    sheet.setAttribute("aria-label", `${state.pages.length + 1}쪽`);
    const scaled = (value) => `${value * state.zoom}pt`;
    sheet.style.setProperty("--page-width", scaled(page.widthPt));
    sheet.style.setProperty("--page-height", scaled(page.heightPt));
    sheet.style.setProperty("--content-width", scaled(page.widthPt - page.marginLeftPt - page.marginRightPt));
    sheet.style.setProperty("--content-height", scaled(page.heightPt - page.marginTopPt - page.marginBottomPt));
    sheet.style.setProperty("--margin-top", scaled(page.marginTopPt));
    sheet.style.setProperty("--margin-right", scaled(page.marginRightPt));
    sheet.style.setProperty("--margin-bottom", scaled(page.marginBottomPt));
    sheet.style.setProperty("--margin-left", scaled(page.marginLeftPt));
    const content = element("div", "page-content");
    const pageNumber = element("div", "page-number", `${state.pages.length + 1}`);
    sheet.appendChild(content);
    wrap.append(sheet, pageNumber);
    elements.pages.appendChild(wrap);
    const descriptor = {
      wrap, sheet, content, sectionIndex, geometry: page, overheight: Boolean(overheight),
      contentWidthPx: (page.widthPt - page.marginLeftPt - page.marginRightPt) * state.zoom * 96 / 72,
      contentHeightPx: (page.heightPt - page.marginTopPt - page.marginBottomPt) * state.zoom * 96 / 72
    };
    state.pages.push(descriptor);
    return descriptor;
  }

  function pageOverflows(page) {
    if (page.overheight) return false;
    if (page.content.clientHeight <= 1) {
      warning("layout-measurement-fallback", "WebKit 배치 측정값이 없어 안전한 근사 분할을 사용했습니다.", null);
      return page.content.scrollHeight > page.contentHeightPx + 1;
    }
    return page.content.scrollHeight > page.content.clientHeight + 1;
  }

  function pageHasContent(page) {
    return page.content.childElementCount > 0;
  }

  function recordFragment(id, start, text, dom) {
    if (!state.fragments[id]) state.fragments[id] = [];
    state.fragments[id].push({ offset: start, text, dom });
  }

  function sourceRuns(node) {
    const text = typeof node.text === "string" ? node.text : "";
    const runs = Array.isArray(node.runs) ? node.runs : [];
    if (runs.length > 0 && runs.map((run) => String(run && run.text || "")).join("") === text) {
      return runs.map((run) => ({
        text: String(run && run.text || ""),
        style: Object.assign({}, node.style || {}, run && run.style || {})
      }));
    }
    return [{ text, style: Core.clone(node.style || {}) }];
  }

  function mappedRunsFor(node) {
    const wholeText = paragraphText(node);
    const draftStyle = state.draft.styles[node.id] || {};
    const cacheKey = JSON.stringify([wholeText, draftStyle.fontFamily, draftStyle.fontSizePt,
      draftStyle.fontFamilyExplicit === true, draftStyle.fontSizePtExplicit === true]);
    const cached = state.runCache.get(node.id);
    if (cached && cached.key === cacheKey) return cached.value;
    const mapped = Core.mappedRuns(typeof node.text === "string" ? node.text : "", sourceRuns(node), wholeText);
    if (mapped.ok) {
      for (const run of mapped.runs) {
        if (draftStyle.fontFamilyExplicit === true) run.style.fontFamily = draftStyle.fontFamily;
        if (draftStyle.fontSizePtExplicit === true) run.style.fontSizePt = draftStyle.fontSizePt;
      }
    }
    state.runCache.set(node.id, { key: cacheKey, value: mapped });
    return mapped;
  }

  function appendRunSlice(container, node, start, end) {
    const wholeText = paragraphText(node);
    const mapped = mappedRunsFor(node);
    if (!mapped.ok) {
      container.textContent = wholeText.slice(start, end);
      return;
    }
    const runs = mapped.runs;
    let runStart = 0;
    for (const run of runs) {
      const runText = String(run.text || "");
      const runEnd = runStart + runText.length;
      const sliceStart = Math.max(start, runStart);
      const sliceEnd = Math.min(end, runEnd);
      if (sliceStart < sliceEnd) {
        const span = document.createElement("span");
        span.textContent = runText.slice(sliceStart - runStart, sliceEnd - runStart);
        applyTextStyle(span, run.style || {});
        container.appendChild(span);
      }
      runStart = runEnd;
    }
  }

  function paragraphFragment(node, start, end, shouldRecord) {
    const text = paragraphText(node);
    const fragment = element("div", `paragraph-fragment${node.editable ? "" : " locked"}`);
    if (node.objectAnchor && text.length === 0) fragment.classList.add("empty-object-anchor");
    fragment.dataset.paragraphId = node.id;
    fragment.dataset.start = String(start);
    fragment.dataset.end = String(end);
    fragment.setAttribute("role", node.editable ? "textbox" : "note");
    fragment.setAttribute("aria-readonly", "true");
    fragment.title = node.editable ? "클릭하여 문단 편집" : (node.reason || "이 내용은 읽기 전용입니다.");
    const style = normalizedStyle(node);
    applyTextStyle(fragment, style);
    // Paragraph geometry belongs on the block, never on individual styled runs.
    // HWP hanging indents push subsequent lines right; CSS also needs a left margin.
    const indent = number(style.textIndentPt, 0);
    const leftMargin = number(style.marginLeftPt, 0) + Math.max(0, -indent);
    fragment.style.marginLeft = `${leftMargin * state.zoom}pt`;
    fragment.style.marginRight = `${number(style.marginRightPt, 0) * state.zoom}pt`;
    fragment.style.textIndent = `${(start === 0 ? indent : 0) * state.zoom}pt`;
    if (start === 0) fragment.style.marginTop = `${number(style.marginTopPt, 0) * state.zoom}pt`;
    if (end === text.length) fragment.style.marginBottom = `${number(style.marginBottomPt, 0) * state.zoom}pt`;
    appendRunSlice(fragment, node, start, end);
    if (text.length === 0) fragment.appendChild(document.createElement("br"));
    if (node.id === state.selectedParagraphId) fragment.classList.add("selected");
    if (shouldRecord) recordFragment(node.id, start, text.slice(start, end), fragment);
    return fragment;
  }

  function largestFittingEnd(page, node, start, boundaries, parent) {
    const startIndex = Math.max(0, boundaries.indexOf(start));
    let low = startIndex + 1;
    let high = boundaries.length - 1;
    let best = start;
    while (low <= high) {
      guardLayout("paragraph-search", node.id);
      const middle = Math.floor((low + high) / 2);
      const candidate = paragraphFragment(node, start, boundaries[middle], false);
      parent.appendChild(candidate);
      const fits = !pageOverflows(page);
      candidate.remove();
      if (fits) { best = boundaries[middle]; low = middle + 1; }
      else high = middle - 1;
    }
    return best;
  }

  function estimatedFittingEnd(page, node, start, boundaries) {
    const style = normalizedStyle(node);
    const fontSize = Math.max(1, number(style.fontSizePt, 10) * state.zoom * 96 / 72);
    const lineHeight = number(style.lineHeightPt, 0) > 0
      ? style.lineHeightPt * state.zoom * 96 / 72
      : fontSize * Math.max(1, number(style.lineHeight, 1.4));
    const lines = Math.max(1, Math.floor(page.contentHeightPx / lineHeight));
    const perLine = Math.max(1, Math.floor(page.contentWidthPx / (fontSize * .95)));
    const count = Math.max(1, lines * perLine);
    const index = Math.max(0, boundaries.indexOf(start));
    return boundaries[Math.min(boundaries.length - 1, index + count)];
  }

  function layoutParagraph(node, context) {
    let page = context.current();
    const text = paragraphText(node);
    const full = paragraphFragment(node, 0, text.length, false);
    page.content.appendChild(full);
    if (!pageOverflows(page)) {
      full.remove();
      page.content.appendChild(paragraphFragment(node, 0, text.length, true));
      return;
    }
    full.remove();
    if (pageHasContent(page)) page = context.next();
    const retry = paragraphFragment(node, 0, text.length, false);
    page.content.appendChild(retry);
    if (!pageOverflows(page)) {
      retry.remove();
      page.content.appendChild(paragraphFragment(node, 0, text.length, true));
      return;
    }
    retry.remove();

    const boundaries = Core.graphemeBoundaries(text);
    if (text.length === 0) {
      page.content.appendChild(paragraphFragment(node, 0, 0, true));
      return;
    }
    let start = 0;
    while (start < text.length) {
      guardLayout("paragraph-split", node.id);
      let end = largestFittingEnd(page, node, start, boundaries, page.content);
      if (end === start && pageHasContent(page)) {
        page = context.next();
        end = largestFittingEnd(page, node, start, boundaries, page.content);
      }
      if (end === start) {
        end = estimatedFittingEnd(page, node, start, boundaries);
        warning("layout-measurement-fallback", "문단을 안전한 글자 경계에서 근사 분할했습니다.", node.id);
        if (end === boundaries[Math.min(boundaries.indexOf(start) + 1, boundaries.length - 1)]) {
          warning("oversized-text-line", "한 줄이 페이지 본문 높이보다 커서 미리보기 페이지가 늘어났습니다.", node.id);
          page.sheet.classList.add("overheight");
          page.overheight = true;
        }
      }
      page.content.appendChild(paragraphFragment(node, start, end, true));
      start = end;
      if (start < text.length) page = context.next();
    }
  }

  function containerChildren(node) {
    const children = Array.isArray(node.children) ? node.children : [];
    const hasObject = children.some((child) => ["table", "image", "shape"].includes(child.type));
    return children.map((child) => hasObject && child.type === "paragraph"
      && child.id === node.anchorParagraphId && paragraphText(child).length === 0
      ? Object.assign({}, child, { objectAnchor: true }) : child);
  }

  function renderStaticNode(node, parent, record) {
    if (!node || typeof node !== "object") return;
    if (node.type === "container") {
      for (const child of containerChildren(node)) renderStaticNode(child, parent, record);
    } else if (node.type === "paragraph") {
      const text = paragraphText(node);
      parent.appendChild(paragraphFragment(node, 0, text.length, record));
    } else if (node.type === "table") {
      parent.appendChild(tableElement(node, Array.isArray(node.rows) ? node.rows : [], record));
    } else if (node.type === "shape") {
      parent.appendChild(shapeElement(node, record));
    } else if (node.type === "image") {
      parent.appendChild(imageElement(node));
    } else {
      parent.appendChild(placeholderElement(node, node.label || "지원하지 않는 문서 요소"));
      warning(node.code || "unsupported-control", node.label || "지원하지 않는 문서 요소를 자리표시자로 표시했습니다.", node.id);
    }
  }

  function cellElement(cell, record, sourceTableWidth) {
    const td = document.createElement("td");
    td.rowSpan = Math.max(1, Math.round(number(cell.rowSpan, 1)));
    td.colSpan = Math.max(1, Math.round(number(cell.colSpan, 1)));
    td.dataset.cellId = cell.id || "";
    applyCellGeometry(td, cell);
    td.style.setProperty("--cell-height", `${Math.max(1, number(cell.heightPt, 1)) * state.zoom}pt`);
    // Keep column proportions when the table is resized or fitted to its parent.
    if (number(cell.widthPt, 0) > 0 && sourceTableWidth > 0) {
      td.style.width = `${Math.min(100, cell.widthPt / sourceTableWidth * 100)}%`;
    }
    for (const child of Array.isArray(cell.children) ? cell.children : []) renderStaticNode(child, td, record);
    td.appendChild(element("span", "cell-location", cell.id || ""));
    return td;
  }

  function tableElement(node, rows, record) {
    const dimensions = normalizedTable(node);
    const allRows = Array.isArray(node.rows) ? node.rows : [];
    const rowHeight = (row) => Math.max(1, ...(Array.isArray(row.cells) ? row.cells : []).map((cell) => number(cell.heightPt, 1)));
    const totalGridHeight = allRows.reduce((sum, row) => sum + rowHeight(row), 0);
    const sliceGridHeight = rows.reduce((sum, row) => sum + rowHeight(row), 0);
    const displayedHeight = rows.length === allRows.length || totalGridHeight <= 0
      ? dimensions.heightPt
      : Math.max(1, dimensions.heightPt * sliceGridHeight / totalGridHeight);
    const table = element("table", "document-table");
    table.dataset.tableId = node.id;
    table.style.setProperty("--table-width", `${dimensions.widthPt * state.zoom}pt`);
    table.style.setProperty("--table-height", `${displayedHeight * state.zoom}pt`);
    const margins = node.outerMarginPt;
    if (margins && typeof margins === "object") {
      for (const side of ["top", "right", "bottom", "left"]) {
        table.style[`margin${side[0].toUpperCase()}${side.slice(1)}`] = `${Math.max(0, number(margins[side], 0)) * state.zoom}pt`;
      }
    }
    const alignment = node.treatAsChar === true ? node.anchorAlignment : (node.position || {}).horzAlign;
    if (["right", "RIGHT"].includes(alignment)) table.style.marginLeft = "auto";
    if (["center", "CENTER"].includes(alignment)) { table.style.marginLeft = "auto"; table.style.marginRight = "auto"; }
    if (node.id === state.selectedTableId) table.classList.add("selected-table");
    const body = document.createElement("tbody");
    for (const row of rows) {
      const tr = document.createElement("tr");
      for (const cell of Array.isArray(row.cells) ? row.cells : []) {
        tr.appendChild(cellElement(cell, record, number(node.widthPt, dimensions.widthPt)));
      }
      body.appendChild(tr);
    }
    table.appendChild(body);
    return table;
  }

  function applyCellGeometry(target, cell) {
    const legacy = Math.max(0, number(cell.paddingPt, 4));
    target.style.setProperty("--cell-padding", ["Top", "Right", "Bottom", "Left"]
      .map((side) => `${Math.max(0, number(cell[`padding${side}Pt`], legacy)) * state.zoom}pt`).join(" "));
    target.style.verticalAlign = ["top", "middle", "bottom"].includes(cell.verticalAlignment) ? cell.verticalAlignment : "top";
  }

  function hasNestedTable(node) {
    function includesTable(child) {
      if (!child || typeof child !== "object") return false;
      if (child.type === "table") return true;
      if (child.type === "container") return (Array.isArray(child.children) ? child.children : []).some(includesTable);
      return false;
    }
    return (Array.isArray(node.rows) ? node.rows : []).some((row) =>
      (Array.isArray(row.cells) ? row.cells : []).some((cell) =>
        (Array.isArray(cell.children) ? cell.children : []).some(includesTable)
      )
    );
  }

  function hasSpans(node) {
    return (Array.isArray(node.rows) ? node.rows : []).some((row) =>
      (Array.isArray(row.cells) ? row.cells : []).some((cell) => number(cell.rowSpan, 1) !== 1 || number(cell.colSpan, 1) !== 1)
    );
  }

  function isSingleCellTextRow(row) {
    if (!row || !Array.isArray(row.cells) || row.cells.length !== 1) return false;
    const cell = row.cells[0];
    if (number(cell.rowSpan, 1) !== 1 || number(cell.colSpan, 1) !== 1) return false;
    const children = Array.isArray(cell.children) ? cell.children : [];
    return children.length > 0 && children.every((child) => child && child.type === "paragraph");
  }

  function isTallSingleCellTextTable(node) {
    const rows = Array.isArray(node.rows) ? node.rows : [];
    return rows.length === 1 && isSingleCellTextRow(rows[0]);
  }

  function appendOverflowWarning(page, node) {
    const card = element("div", "overflow-card");
    card.appendChild(element("strong", "", "페이지보다 큰 개체"));
    card.appendChild(element("span", "", "병합 셀 또는 중첩 표의 구조를 지키기 위해 이 페이지의 높이를 늘려 표시합니다."));
    page.content.appendChild(card);
    warning("oversized-indivisible-object", "나눌 수 없는 개체가 페이지보다 커서 확장 페이지에 표시되었습니다.", node.id);
  }

  function layoutIndivisibleTable(node, context) {
    let page = context.current();
    let trial = tableElement(node, node.rows || [], false);
    page.content.appendChild(trial);
    if (pageOverflows(page) && pageHasContent(page) && page.content.childElementCount > 1) {
      trial.remove();
      page = context.next();
      trial = tableElement(node, node.rows || [], false);
      page.content.appendChild(trial);
    }
    if (pageOverflows(page)) {
      trial.remove();
      page.sheet.classList.add("overheight");
      page.overheight = true;
      appendOverflowWarning(page, node);
    } else {
      trial.remove();
    }
    page.content.appendChild(tableElement(node, node.rows || [], true));
  }

  function layoutRowSplittableTable(node, context) {
    const rows = Array.isArray(node.rows) ? node.rows : [];
    let rowIndex = 0;
    while (rowIndex < rows.length) {
      guardLayout("table-row", node.id);
      let page = context.current();
      if (pageHasContent(page)) {
        const firstTrial = tableElement(node, [rows[rowIndex]], false);
        page.content.appendChild(firstTrial);
        const cannotStart = pageOverflows(page);
        firstTrial.remove();
        if (cannotStart) page = context.next();
      }
      let end = rowIndex;
      while (end < rows.length) {
        guardLayout("table-row-search", node.id);
        const trial = tableElement(node, rows.slice(rowIndex, end + 1), false);
        page.content.appendChild(trial);
        const fits = !pageOverflows(page);
        trial.remove();
        if (!fits) break;
        end += 1;
      }
      if (end === rowIndex) {
        const singleRowNode = Object.assign({}, node, { rows: [rows[rowIndex]] });
        if (isSingleCellTextRow(rows[rowIndex])) layoutTallSingleCellTable(singleRowNode, context);
        else layoutIndivisibleTable(singleRowNode, context);
        rowIndex += 1;
      } else {
        page.content.appendChild(tableElement(node, rows.slice(rowIndex, end), true));
        rowIndex = end;
        if (rowIndex < rows.length) context.next();
      }
    }
  }

  function makeSingleCellShell(node, sourceCell, page) {
    const shell = tableElement(Object.assign({}, node, { rows: [] }), [], false);
    shell.style.setProperty("--table-height", `${state.zoom}pt`);
    const body = shell.querySelector("tbody");
    const tr = document.createElement("tr");
    const cell = document.createElement("td");
    cell.dataset.cellId = sourceCell.id || "";
    applyCellGeometry(cell, sourceCell);
    cell.style.setProperty("--cell-height", `${state.zoom}pt`);
    tr.appendChild(cell);
    body.appendChild(tr);
    page.content.appendChild(shell);
    return { shell, cell };
  }

  function layoutTallSingleCellTable(node, context) {
    const sourceCell = node.rows[0].cells[0];
    const children = sourceCell.children;
    let page = context.current();
    let holder = makeSingleCellShell(node, sourceCell, page);
    if (pageOverflows(page) && page.content.childElementCount > 1) {
      holder.shell.remove();
      page = context.next();
      holder = makeSingleCellShell(node, sourceCell, page);
    }
    for (let childIndex = 0; childIndex < children.length; childIndex += 1) {
      const paragraph = children[childIndex];
      const text = state.draft.texts[paragraph.id] || "";
      const boundaries = Core.graphemeBoundaries(text);
      let start = 0;
      if (text.length === 0) {
        holder.cell.appendChild(paragraphFragment(paragraph, 0, 0, true));
        continue;
      }
      while (start < text.length) {
        guardLayout("table-text-split", paragraph.id);
        let end = largestFittingEnd(page, paragraph, start, boundaries, holder.cell);
        if (end === start) {
          if (!holder.cell.querySelector(".paragraph-fragment")) holder.shell.remove();
          page = context.next();
          holder = makeSingleCellShell(node, sourceCell, page);
          end = largestFittingEnd(page, paragraph, start, boundaries, holder.cell);
        }
        if (end === start) {
          end = estimatedFittingEnd(page, paragraph, start, boundaries);
          warning("layout-measurement-fallback", "표 안 문단을 안전한 글자 경계에서 근사 분할했습니다.", paragraph.id);
          if (end === boundaries[Math.min(boundaries.indexOf(start) + 1, boundaries.length - 1)]) {
            page.sheet.classList.add("overheight");
            page.overheight = true;
            warning("oversized-text-line", "표 안의 한 줄이 페이지 본문보다 커서 미리보기 페이지가 늘어났습니다.", paragraph.id);
          }
        }
        holder.cell.appendChild(paragraphFragment(paragraph, start, end, true));
        start = end;
        if (start < text.length) {
          page = context.next();
          holder = makeSingleCellShell(node, sourceCell, page);
        }
      }
      if (childIndex < children.length - 1 && pageOverflows(page)) {
        page = context.next();
        holder = makeSingleCellShell(node, sourceCell, page);
      }
    }
  }

  function layoutTable(node, context) {
    if (isTallSingleCellTextTable(node)) {
      // Keep a fitting table's declared height; only split genuinely tall content.
      let page = context.current();
      let trial = tableElement(node, node.rows || [], false);
      page.content.appendChild(trial);
      let fits = !pageOverflows(page);
      trial.remove();
      if (!fits && pageHasContent(page)) {
        page = context.next();
        trial = tableElement(node, node.rows || [], false);
        page.content.appendChild(trial);
        fits = !pageOverflows(page);
        trial.remove();
      }
      if (fits) page.content.appendChild(tableElement(node, node.rows || [], true));
      else layoutTallSingleCellTable(node, context);
    } else if (hasNestedTable(node) || hasSpans(node)) {
      layoutIndivisibleTable(node, context);
    } else {
      layoutRowSplittableTable(node, context);
    }
  }

  function imageElement(node) {
    const source = typeof node.src === "string" ? node.src : "";
    if (!/^data:image\/(png|jpeg|gif);base64,[a-z0-9+/]+=*$/iu.test(source)) {
      warning("unsupported-image", "지원하지 않는 그림을 자리표시자로 표시했습니다.", node.id);
      return placeholderElement(node, node.alt || "표시할 수 없는 그림");
    }
    const image = document.createElement("img");
    image.className = "document-image";
    image.dataset.nodeId = node.id || "";
    image.decoding = "sync";
    image.loading = "eager";
    image.alt = typeof node.alt === "string" ? node.alt : "문서 그림";
    const generation = state.imageGeneration;
    let resource = state.imageResources.get(source);
    if (!resource) {
      resource = { width: 0, height: 0, settled: false, failed: false };
      state.imageResources.set(source, resource);
    }
    const settle = () => {
      if (generation !== state.imageGeneration || resource.settled) return;
      resource.width = image.naturalWidth;
      resource.height = image.naturalHeight;
      resource.failed = resource.width <= 0 || resource.height <= 0;
      resource.settled = true;
      // Recompute pagination once per source, even if the measuring element
      // was removed while trying page/table fragments. Old documents cannot
      // trigger new-document renders. IME and PDF work keep their own flush.
      if (!state.busy) scheduleRender();
    };
    image.addEventListener("load", settle, { once: true });
    image.addEventListener("error", settle, { once: true });
    let width = number(node.widthPt, 0), height = number(node.heightPt, 0);
    if (resource.width > 0 && resource.height > 0) {
      if (width <= 0 && height <= 0) {
        width = resource.width * 72 / 96;
        height = resource.height * 72 / 96;
      } else if (height <= 0) height = width * resource.height / resource.width;
      else if (width <= 0) width = height * resource.width / resource.height;
    }
    if (width > 0) image.style.width = `${width * state.zoom}pt`;
    if (height > 0) image.style.height = `${height * state.zoom}pt`;
    if (resource.failed) warning("image-load-failed", "그림을 불러오지 못했습니다.", node.id);
    image.src = source;
    return image;
  }

  function placeholderElement(node, label) {
    const placeholder = element("div", "asset-placeholder");
    placeholder.dataset.nodeId = node && node.id ? node.id : "";
    placeholder.appendChild(element("strong", "", label));
    if (node && node.reason) placeholder.appendChild(element("div", "", node.reason));
    return placeholder;
  }

  function layoutImage(node, context) {
    let page = context.current();
    const geometry = state.model.sections[page.sectionIndex].page || {};
    const contentWidth = number(geometry.widthPt, 595) - number(geometry.marginLeftPt, 56) - number(geometry.marginRightPt, 56);
    if (number(node.widthPt, 0) > contentWidth + .5) {
      warning("oversized-horizontal-object", "그림이 페이지 본문 너비보다 넓어 축소 표시됩니다.", node.id);
    }
    let image = imageElement(node);
    page.content.appendChild(image);
    if (pageOverflows(page) && page.content.childElementCount > 1) {
      image.remove();
      page = context.next();
      image = imageElement(node);
      page.content.appendChild(image);
    }
    if (pageOverflows(page)) {
      image.remove();
      page.sheet.classList.add("overheight");
      page.overheight = true;
      appendOverflowWarning(page, node);
      page.content.appendChild(imageElement(node));
    }
  }

  function layoutUnknown(node, context) {
    const placeholder = placeholderElement(node || {}, (node && node.label) || "지원하지 않는 문서 요소");
    context.current().content.appendChild(placeholder);
    warning((node && node.code) || "unsupported-control", (node && node.label) || "지원하지 않는 문서 요소를 자리표시자로 표시했습니다.", node && node.id);
  }

  function shapeElement(node, record) {
    const shape = element("div", `document-shape shape-${node.kind}`);
    shape.dataset.nodeId = node.id || "";
    shape.style.width = `${Math.max(0.01, number(node.widthPt, 1)) * state.zoom}pt`;
    shape.style.minHeight = `${Math.max(0.01, number(node.heightPt, 1)) * state.zoom}pt`;
    const stroke = /^#[0-9a-f]{6}$/iu.test(node.strokeColor || "") ? node.strokeColor : "transparent";
    if (node.kind === "line") {
      shape.style.borderTop = `${Math.max(0.1, number(node.strokeWidthPt, 0.5)) * state.zoom}pt solid ${stroke}`;
    } else {
      shape.style.border = `${Math.max(0, number(node.strokeWidthPt, 0)) * state.zoom}pt solid ${stroke}`;
      if (/^#[0-9a-f]{6}$/iu.test(node.fillColor || "")) shape.style.backgroundColor = node.fillColor;
      for (const child of node.children || []) renderStaticNode(child, shape, record);
    }
    return shape;
  }

  function alignedObjectPosition(position, horizontal, vertical, width, height) {
    const scale = state.zoom * 96 / 72;
    let x = horizontal.left + number(position.horzOffsetPt, 0) * scale;
    let y = vertical.top + number(position.vertOffsetPt, 0) * scale;
    if (position.horzAlign === "RIGHT") x += horizontal.width - width;
    else if (position.horzAlign === "CENTER") x += (horizontal.width - width) / 2;
    if (position.vertAlign === "BOTTOM") y += vertical.height - height;
    else if (position.vertAlign === "CENTER") y += (vertical.height - height) / 2;
    return { x, y };
  }

  function layoutShape(node, context) {
    let page = context.current();
    const position = node.position || {};
    const supportedFloating = position.treatAsChar === false && position.horzRelTo === "PAPER" && position.vertRelTo === "PAPER";
    const shape = shapeElement(node, supportedFloating);
    if (position.treatAsChar === false) {
      if (position.horzRelTo !== "PAPER" || position.vertRelTo !== "PAPER") {
        layoutUnknown({ id: node.id, code: "unsupported-control", label: "지원하지 않는 도형 위치 기준", reason: "이 부동 도형의 위치 관계는 아직 출력할 수 없습니다." }, context);
        return;
      }
      const paper = page.sheet.getBoundingClientRect();
      const coordinates = alignedObjectPosition(position, paper, paper,
        number(node.widthPt, 1) * state.zoom * 96 / 72,
        number(node.heightPt, 1) * state.zoom * 96 / 72);
      shape.classList.add("floating-shape");
      shape.style.left = `${coordinates.x - paper.left}px`;
      shape.style.top = `${coordinates.y - paper.top}px`;
      page.sheet.appendChild(shape);
      return;
    }
    page.content.appendChild(shape);
    if (pageOverflows(page) && page.content.childElementCount > 1) {
      shape.remove();
      page = context.next();
      page.content.appendChild(shape);
    }
    shape.remove();
    page.content.appendChild(shapeElement(node, true));
    if (pageOverflows(page)) {
      page.overheight = true;
      page.sheet.classList.add("overheight");
      warning("oversized-indivisible-object", "도형이 페이지 본문 높이를 넘습니다.", node.id);
    }
  }

  function layoutNode(node, context) {
    if (!node || typeof node !== "object") return;
    if (node.pageBreakBefore) {
      const breakKey = `${context.sectionIndex}|${node.anchorParagraphId || node.id}`;
      if (!state.appliedPageBreaks.has(breakKey)) {
        state.appliedPageBreaks.add(breakKey);
        if (pageHasContent(context.current())) context.next();
      }
    }
    const startPage = state.pages.indexOf(context.current());
    const counts = state.pages.map((page) => page.content.childElementCount);
    if (node.type === "container") {
      for (const child of containerChildren(node)) layoutNode(child, context);
    } else if (node.type === "paragraph") layoutParagraph(node, context);
    else if (node.type === "table") layoutTable(node, context);
    else if (node.type === "image") layoutImage(node, context);
    else if (node.type === "shape" && ["rect", "line"].includes(node.kind)) layoutShape(node, context);
    else layoutUnknown(node, context);
    const anchor = node.anchorParagraphId || (node.type === "paragraph" ? node.id : null);
    if (anchor) {
      const key = `${context.sectionIndex}|${anchor}`;
      const actualPage = state.pages.findIndex((page, index) => index >= startPage
        && page.content.childElementCount > (counts[index] || 0));
      if (!state.anchorPages.has(key)) state.anchorPages.set(key, actualPage >= 0 ? actualPage : startPage);
    }
  }

  function imageDiagnostic(image, bounds) {
    const rect = image.getBoundingClientRect();
    const pageIndex = state.pages.findIndex((page) => page.sheet.contains(image));
    return {
      id: image.dataset.nodeId || null, complete: image.complete,
      naturalWidth: image.naturalWidth, naturalHeight: image.naturalHeight,
      renderedWidth: rect.width, renderedHeight: rect.height, pageIndex,
      fullyContained: Boolean(bounds && rect.width > 0 && rect.height > 0 && containsRect(bounds, rect))
    };
  }

  function containsRect(bounds, rect) {
    return rect.left >= bounds.left - 1 && rect.top >= bounds.top - 1
      && rect.right <= bounds.right + 1 && rect.bottom <= bounds.bottom + 1;
  }

  function overlapsRect(a, b) {
    return Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1
      && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
  }

  function renderRepeatingRegion(definition, page, pageIndex) {
    const geometry = page.geometry;
    const isHeader = definition.kind === "header";
    const bandHeight = isHeader ? geometry.headerHeightPt : geometry.footerHeightPt;
    const host = element("div", `repeating-region repeating-${definition.kind}`);
    host.dataset.regionId = definition.id;
    host.style.left = `${geometry.marginLeftPt * state.zoom}pt`;
    host.style.width = `${(geometry.widthPt - geometry.marginLeftPt - geometry.marginRightPt) * state.zoom}pt`;
    host.style.height = `${bandHeight * state.zoom}pt`;
    host.style[isHeader ? "top" : "bottom"] = `${(isHeader ? geometry.paperMarginTopPt : geometry.paperMarginBottomPt) * state.zoom}pt`;
    const flow = element("div", "repeating-flow");
    const alignment = (definition.geometry || {}).vertAlign || (definition.kind === "footer" ? "BOTTOM" : "TOP");
    if (alignment === "BOTTOM") flow.style.bottom = "0";
    else if (alignment === "CENTER") { flow.style.top = "50%"; flow.style.transform = "translateY(-50%)"; }
    else flow.style.top = "0";
    host.appendChild(flow);
    page.sheet.appendChild(host);
    const floating = [];
    const inlineImages = [];
    const anchors = new Map();
    const paragraphNodes = new Map();
    let lastParagraph = null;
    function append(node) {
      if (node.type === "container") { for (const child of containerChildren(node)) append(child); }
      else if (node.type === "paragraph") {
        const paragraph = paragraphFragment(Object.assign({}, node, { editable: false }), 0, paragraphText(node).length, false);
        flow.appendChild(paragraph);
        anchors.set(node.id, paragraph);
        paragraphNodes.set(node.id, node);
        lastParagraph = paragraph;
      } else if (node.type === "image" && node.position) {
        const item = { node, anchor: anchors.get(node.anchorParagraphId) || lastParagraph };
        if (node.position.treatAsChar === false) floating.push(item);
        else inlineImages.push(item);
      } else renderStaticNode(node, flow, false);
    }
    for (const node of definition.children || []) append(node);
    for (const item of inlineImages) {
      const image = imageElement(item.node);
      const anchor = anchors.get(item.node.anchorParagraphId) || item.anchor;
      image.classList.add("repeating-inline-image");
      (anchor || flow).appendChild(image);
    }
    for (const item of floating) {
      const image = imageElement(item.node);
      const position = item.node.position;
      const anchor = anchors.get(item.node.anchorParagraphId) || item.anchor;
      const band = host.getBoundingClientRect();
      const anchorRect = anchor ? anchor.getBoundingClientRect() : band;
      const width = number(item.node.widthPt, 0) * state.zoom * 96 / 72;
      const height = number(item.node.heightPt, 0) * state.zoom * 96 / 72;
      const scale = state.zoom * 96 / 72;
      const paragraphNode = anchor ? paragraphNodes.get(anchor.dataset.paragraphId) : null;
      const paragraphStyle = paragraphNode ? normalizedStyle(paragraphNode) : {};
      // A hanging indent moves glyphs, not the paragraph's reference origin.
      const leftInset = number(paragraphStyle.marginLeftPt, 0) * scale;
      const rightInset = number(paragraphStyle.marginRightPt, 0) * scale;
      const paragraphBounds = {
        left: band.left + leftInset, top: anchorRect.top,
        width: Math.max(0, band.width - leftInset - rightInset), height: anchorRect.height
      };
      const horizontal = {
        PAPER: page.sheet.getBoundingClientRect(), PAGE: page.content.getBoundingClientRect(),
        COLUMN: band, PARA: paragraphBounds
      }[position.horzRelTo];
      const vertical = {
        PAPER: page.sheet.getBoundingClientRect(), PAGE: page.content.getBoundingClientRect(), PARA: paragraphBounds
      }[position.vertRelTo];
      if (!horizontal || !vertical || (position.horzRelTo === "PARA" || position.vertRelTo === "PARA") && !anchor) {
        flow.appendChild(placeholderElement(item.node, "지원하지 않는 반복 그림 위치 기준"));
        warning("unsupported-control", "머리말·꼬리말 그림의 위치 기준을 처리할 수 없습니다.", item.node.id);
        continue;
      }
      const coordinates = alignedObjectPosition(position, horizontal, vertical, width, height);
      const x = coordinates.x - band.left, y = coordinates.y - band.top;
      image.classList.add("repeating-floating-image");
      image.style.left = `${x}px`;
      image.style.top = `${y}px`;
      host.appendChild(image);
    }
    state.repeatingRegions.push({ definition, page, pageIndex, host });
  }

  function renderRepeatingRegions() {
    state.pages.forEach((page, pageIndex) => {
      // Section-local definitions override matching inherited definitions.
      const candidates = [];
      for (let sectionIndex = 0; sectionIndex <= page.sectionIndex; sectionIndex += 1) {
        const section = state.model.sections[sectionIndex];
        const firstPage = state.pages.findIndex((item) => item.sectionIndex === sectionIndex);
        for (const definition of section.headerFooters || []) {
          const start = definition.startParagraphId
            ? state.anchorPages.get(`${sectionIndex}|${definition.startParagraphId}`) : firstPage;
          if (pageIndex < (start === undefined ? firstPage : start)) continue;
          const parity = pageIndex % 2 === 0 ? "ODD" : "EVEN";
          if (definition.applyPageType && !["BOTH", parity].includes(definition.applyPageType)) continue;
          candidates.push({ definition, sectionIndex });
        }
      }
      for (const kind of ["header", "footer"]) {
        const matching = candidates.filter((item) => item.definition.kind === kind).sort((a, b) =>
          a.sectionIndex - b.sectionIndex || number(a.definition.sourceOrder, 0) - number(b.definition.sourceOrder, 0));
        if (matching.length) renderRepeatingRegion(matching[matching.length - 1].definition, page, pageIndex);
      }
    });
  }

  function repeatingInkRects(host) {
    const rects = Array.from(host.querySelectorAll(".document-image, .document-table, .document-shape"))
      .map((node) => node.getBoundingClientRect());
    // Blank paragraph boxes and trailing spaces do not paint. Test actual glyph
    // ranges instead, so empty header/footer lines cannot cause false clipping.
    const walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT);
    let text;
    while ((text = walker.nextNode())) {
      const value = text.textContent || "";
      const first = value.search(/\S/u);
      if (first < 0) continue;
      const end = value.replace(/\s+$/u, "").length;
      const range = document.createRange();
      range.setStart(text, first);
      range.setEnd(text, end);
      rects.push(...Array.from(range.getClientRects()));
    }
    return rects;
  }

  function repeatingDiagnostics() {
    return state.repeatingRegions.map(({ definition, page, pageIndex, host }) => {
      const paper = page.sheet.getBoundingClientRect();
      const body = page.content.getBoundingClientRect();
      const ink = repeatingInkRects(host);
      return {
        pageIndex, sectionId: state.model.sections[page.sectionIndex].id,
        id: definition.id, kind: definition.kind, sourceOrder: definition.sourceOrder,
        paragraphTexts: Array.from(host.querySelectorAll(".paragraph-fragment")).map((paragraph) => ({ id: paragraph.dataset.paragraphId, text: paragraph.textContent })),
        fullyContained: ink.every((rect) => containsRect(paper, rect)),
        bodyOverlap: ink.some((rect) => overlapsRect(body, rect)),
        images: Array.from(host.querySelectorAll("img.document-image")).map((image) => imageDiagnostic(image, paper))
      };
    });
  }

  function render() {
    state.renderQueued = false;
    if (!state.model || state.composing) return;
    const savedSelection = state.selectedParagraphId && document.activeElement === elements.editor
      ? { start: elements.editor.selectionStart, end: elements.editor.selectionEnd }
      : { start: state.selectionStart, end: state.selectionEnd };
    elements.pages.replaceChildren();
    state.pages = [];
    state.fragments = {};
    state.anchorPages.clear();
    state.appliedPageBreaks.clear();
    state.repeatingRegions = [];
    state.warnings = [];
    state.renderOperations = 0;
    for (const raw of Array.isArray(state.model.warnings) ? state.model.warnings : []) {
      if (typeof raw === "string") warning("document-warning", raw, null);
      else if (raw && typeof raw === "object") warning(raw.code || "document-warning", raw.message || raw.reason || "문서 경고", raw.id);
    }

    (state.viewModel || state.model).sections.forEach((section, sectionIndex) => {
      let currentPage = makePage(section, sectionIndex, false);
      const context = {
        sectionIndex,
        current: () => currentPage,
        next: () => { currentPage = makePage(section, sectionIndex, false); return currentPage; }
      };
      for (const node of Array.isArray(section.children) ? section.children : []) {
        layoutNode(node, context);
      }
    });

    renderRepeatingRegions();

    for (const table of elements.pages.querySelectorAll(".document-table")) {
      const node = state.tables.get(table.dataset.tableId);
      if (node && table.getBoundingClientRect().width + 1 < normalizedTable(node).widthPt * state.zoom * 96 / 72) {
        warning("table-width-fitted", "본문 또는 상위 셀 너비에 맞춰 표를 표시했습니다. 원본 표 크기는 유지됩니다.", node.id);
      }
    }
    elements.empty.hidden = state.pages.length > 0;
    elements.pageStatus.textContent = `${state.pages.length}쪽`;
    refreshWarnings();
    state.selectionStart = savedSelection.start;
    state.selectionEnd = savedSelection.end;
    if (state.selectedParagraphId) {
      const text = elements.editor.hidden
        ? state.draft.texts[state.selectedParagraphId] || ""
        : elements.editor.value;
      state.selectionStart = Math.min(text.length, state.selectionStart);
      state.selectionEnd = Math.min(text.length, state.selectionEnd);
      elements.editor.selectionStart = state.selectionStart;
      elements.editor.selectionEnd = state.selectionEnd;
      requestAnimationFrame(positionEditor);
    }
    refreshControls();
  }

  function scheduleRender() {
    if (state.renderQueued || state.composing) return;
    state.renderQueued = true;
    requestAnimationFrame(() => {
      if (state.renderQueued) render();
    });
  }

  function flushRender() {
    if (state.composing) return false;
    if (state.renderQueued) render();
    positionEditor();
    return true;
  }

  const PDF_EXPORT_CSS = `
/* HWPX Studio PDF snapshot v1 */
html, body {
  width: auto !important;
  min-width: 0 !important;
  height: auto !important;
  min-height: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
  overflow: visible !important;
  background: #fff !important;
}
body {
  display: block !important;
  font-family: "Apple SD Gothic Neo", "Noto Sans CJK KR", sans-serif;
}
body > [data-pdf-page] {
  display: block !important;
  background: #fff !important;
  margin: 0 !important;
  padding: 0 !important;
  overflow: hidden !important;
  border: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  break-after: page;
}
body > [data-pdf-page]:last-child { break-after: auto; }
[data-pdf-page] .page-content { overflow: visible !important; }
[data-pdf-page] .paragraph-fragment,
[data-pdf-page] .paragraph-fragment:hover,
[data-pdf-page] .paragraph-fragment.selected,
[data-pdf-page] .paragraph-fragment.locked,
[data-pdf-page] .document-table.selected-table {
  cursor: default !important;
  outline: 0 !important;
  box-shadow: none !important;
}
`;

  function uniqueMessages(messages) {
    return Array.from(new Set(messages.filter((message) => typeof message === "string" && message.length > 0)));
  }

  function pageGeometry(page) {
    const section = state.model.sections[page.sectionIndex] || {};
    const geometry = section.page || {};
    return {
      widthPt: number(geometry.widthPt, 595),
      heightPt: number(geometry.heightPt, 842)
    };
  }

  function boundedWait(promise, timeoutMs) {
    return new Promise((resolve) => {
      let finished = false;
      const complete = (value) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        resolve(value);
      };
      const timer = setTimeout(() => complete(false), timeoutMs);
      Promise.resolve(promise).then(() => complete(true), () => complete(false));
    });
  }

  async function waitForImage(image) {
    if (!image.complete) {
      const settled = new Promise((resolve) => {
        image.addEventListener("load", resolve, { once: true });
        image.addEventListener("error", resolve, { once: true });
      });
      if (!await boundedWait(settled, 5000)) return false;
    }
    if (image.naturalWidth <= 0 || image.naturalHeight <= 0) return false;
    return typeof image.decode !== "function" || await boundedWait(image.decode(), 5000);
  }

  async function waitForPDFResources() {
    let fontsReady = true;
    if (document.fonts && document.fonts.ready) {
      fontsReady = await boundedWait(document.fonts.ready, 5000);
    }
    await Promise.all(Array.from(elements.pages.querySelectorAll("img.document-image")).map(waitForImage));
    // Intrinsic image dimensions and fonts may have changed since the initial
    // synchronous layout. Repaginate before freezing the PDF DOM.
    render();
    const images = Array.from(elements.pages.querySelectorAll("img.document-image"));
    const imageResults = await Promise.all(images.map(waitForImage));
    // Hidden/background WebKit windows may suspend animation frames. Measuring
    // layout after resources settle is sufficient; PDF painting is done by WebKit.
    elements.pages.getBoundingClientRect();
    await new Promise((resolve) => setTimeout(resolve, 0));
    const layoutReady = state.pages.every((page) => page.content.clientWidth > 1 && page.content.clientHeight > 1);
    return { images, imageResults, fontsReady, layoutReady };
  }

  function requestedFonts() {
    const fonts = new Set();
    const paragraphs = new Map(state.paragraphs);
    function collect(node) {
      if (node.type === "paragraph") paragraphs.set(node.id, node);
      for (const child of node.children || []) collect(child);
      for (const row of node.rows || []) for (const cell of row.cells || []) for (const child of cell.children || []) collect(child);
    }
    for (const section of state.model.sections) for (const region of section.headerFooters || []) for (const child of region.children || []) collect(child);
    for (const paragraph of paragraphs.values()) {
      const paragraphStyle = normalizedStyle(paragraph);
      if (Core.safeFontFamily(paragraphStyle.fontFamily)) fonts.add(paragraphStyle.fontFamily);
      for (const run of sourceRuns(paragraph)) {
        if (run && run.style && Core.safeFontFamily(run.style.fontFamily)) fonts.add(run.style.fontFamily);
      }
    }
    return Array.from(fonts);
  }

  function cleanPDFPage(page, index) {
    const clone = page.sheet.cloneNode(true);
    clone.dataset.pdfPage = String(index + 1);
    clone.classList.remove("overheight");
    clone.removeAttribute("aria-label");
    for (const node of clone.querySelectorAll(".cell-location, .overflow-card")) node.remove();
    for (const node of clone.querySelectorAll(".paragraph-fragment")) {
      node.classList.remove("selected");
      node.removeAttribute("title");
      node.removeAttribute("role");
      node.removeAttribute("aria-readonly");
      delete node.dataset.paragraphId;
      delete node.dataset.start;
      delete node.dataset.end;
    }
    for (const node of clone.querySelectorAll(".document-table")) {
      node.classList.remove("selected-table");
      delete node.dataset.tableId;
    }
    for (const node of clone.querySelectorAll("[data-cell-id]")) delete node.dataset.cellId;
    for (const node of clone.querySelectorAll("[title]")) node.removeAttribute("title");
    return clone;
  }

  function pdfPreflight(resources) {
    const warnings = ["PDF는 HWPX 문서를 근사 배치한 결과이므로 한컴오피스와 줄바꿈·개체 위치가 다를 수 있습니다."];
    const blockingIssues = [];
    for (const item of state.warnings) {
      if (item.code === "oversized-horizontal-object") {
        blockingIssues.push(`가로로 잘릴 수 있는 개체가 있습니다${item.id ? `: ${item.id}` : ""}.`);
      } else if (["oversized-indivisible-object", "oversized-text-line"].includes(item.code)) {
        blockingIssues.push(`한 페이지 높이를 넘는 내용이 있습니다${item.id ? `: ${item.id}` : ""}.`);
      } else if (["unsupported-control", "unsupported-image"].includes(item.code)) {
        blockingIssues.push(`PDF로 안전하게 출력할 수 없는 문서 요소가 있습니다${item.id ? `: ${item.id}` : ""}.`);
      } else {
        warnings.push(item.message);
      }
    }
    state.pages.forEach((page, index) => {
      const label = `${index + 1}쪽`;
      if (page.overheight || page.sheet.classList.contains("overheight")) {
        blockingIssues.push(`${label}의 내용이 용지 높이를 넘습니다.`);
      }
      if (page.content.clientWidth <= 1 || page.content.clientHeight <= 1) {
        blockingIssues.push(`${label}의 출력 크기를 측정할 수 없습니다.`);
        return;
      }
      if (page.content.scrollWidth > page.content.clientWidth + 1) {
        blockingIssues.push(`${label}의 내용이 용지 너비를 넘어 잘릴 수 있습니다.`);
      }
      if (!page.overheight && page.content.scrollHeight > page.content.clientHeight + 1) {
        blockingIssues.push(`${label}의 내용이 용지 높이를 넘어 잘릴 수 있습니다.`);
      }
    });
    for (const region of repeatingDiagnostics()) {
      if (!region.fullyContained) blockingIssues.push(`${region.pageIndex + 1}쪽 ${region.kind === "header" ? "머리말" : "꼬리말"}이 용지 밖으로 잘릴 수 있습니다.`);
      if (region.bodyOverlap) blockingIssues.push(`${region.pageIndex + 1}쪽 머리말·꼬리말이 본문과 겹칩니다.`);
    }
    for (const shape of elements.pages.querySelectorAll(".floating-shape")) {
      const page = state.pages.find((item) => item.sheet.contains(shape));
      if (!page || !containsRect(page.sheet.getBoundingClientRect(), shape.getBoundingClientRect())) blockingIssues.push("도형이 용지 밖으로 잘릴 수 있습니다.");
    }
    const placeholders = elements.pages.querySelectorAll(".asset-placeholder, .control-placeholder");
    if (placeholders.length > 0) blockingIssues.push(`지원하지 않는 문서 요소 ${placeholders.length}개가 자리표시자로 남아 있습니다.`);
    const missingImages = resources.images.filter((image, index) => !resources.imageResults[index] || !image.complete || image.naturalWidth <= 0 || image.naturalHeight <= 0);
    if (missingImages.length > 0) blockingIssues.push(`불러오지 못한 그림이 ${missingImages.length}개 있습니다.`);
    if (!resources.fontsReady) warnings.push("글꼴 준비가 제한 시간 안에 끝나지 않아 일부 글꼴이 대체될 수 있습니다.");
    if (!resources.layoutReady) blockingIssues.push("PDF 출력 배치를 제한 시간 안에 확인하지 못했습니다.");
    const availableFonts = Array.isArray(state.model.availableFonts) ? state.model.availableFonts : [];
    if (availableFonts.length > 0) {
      const inventory = new Set(availableFonts.map((font) => String(font).toLocaleLowerCase("ko")));
      const missingFonts = requestedFonts().filter((font) => !inventory.has(font.toLocaleLowerCase("ko")));
      if (missingFonts.length > 0) warnings.push(`사용할 수 없는 글꼴이 대체될 수 있습니다: ${missingFonts.join(", ")}`);
    }
    return { warnings: uniqueMessages(warnings), blockingIssues: uniqueMessages(blockingIssues) };
  }

  async function preparePDFSnapshot() {
    if (!state.model) throw new Error("PDF로 내보낼 문서가 없습니다.");
    if (state.composing) throw new Error("한글 조합을 마친 뒤 PDF로 내보내 주세요.");
    if (state.busy) throw new Error("다른 작업이 끝난 뒤 PDF로 내보내 주세요.");

    if (!elements.editor.hidden && state.selectedParagraphId) {
      const selectionStart = elements.editor.selectionStart;
      const selectionEnd = elements.editor.selectionEnd;
      if (!commitText(state.selectedParagraphId, elements.editor.value, "pdf-export", false)) {
        throw new Error("편집 중인 문단을 PDF에 반영하지 못했습니다.");
      }
      state.selectionStart = selectionStart;
      state.selectionEnd = selectionEnd;
    }

    const original = {
      zoom: state.zoom,
      selectionStart: state.selectionStart,
      selectionEnd: state.selectionEnd,
      scrollLeft: elements.viewport.scrollLeft,
      scrollTop: elements.viewport.scrollTop,
      editorFocused: document.activeElement === elements.editor
    };
    setBusy(true);
    try {
      state.zoom = 1;
      elements.zoomValue.textContent = "100%";
      render();
      const resources = await waitForPDFResources();
      const preflight = pdfPreflight(resources);
      const pages = state.pages.map(pageGeometry);
      const bodyHTML = state.pages.map((page, index) => cleanPDFPage(page, index).outerHTML).join("");
      return {
        bodyHTML,
        pages,
        warnings: preflight.warnings,
        blockingIssues: preflight.blockingIssues,
        exportCSS: PDF_EXPORT_CSS
      };
    } finally {
      try {
        state.zoom = original.zoom;
        elements.zoomValue.textContent = `${Math.round(state.zoom * 100)}%`;
        render();
        state.selectionStart = original.selectionStart;
        state.selectionEnd = original.selectionEnd;
        if (!elements.editor.hidden) {
          const length = elements.editor.value.length;
          elements.editor.selectionStart = Math.min(length, original.selectionStart);
          elements.editor.selectionEnd = Math.min(length, original.selectionEnd);
          if (original.editorFocused) elements.editor.focus({ preventScroll: true });
        }
        elements.viewport.scrollLeft = original.scrollLeft;
        elements.viewport.scrollTop = original.scrollTop;
      } finally {
        setBusy(false);
      }
    }
  }

  function refreshWarnings() {
    const count = state.warnings.length;
    elements.warningStatus.textContent = count ? `경고 ${count}건` : "경고 없음";
    elements.warningStatus.classList.toggle("warning", count > 0);
    if (count) {
      elements.warningStatus.title = state.warnings.map((item) => `${item.code}: ${item.message}`).join("\n");
    } else elements.warningStatus.removeAttribute("title");
  }

  function showNotice(message, error) {
    elements.notice.textContent = message || "";
    elements.notice.hidden = !message;
    elements.notice.classList.toggle("error", Boolean(error));
  }

  function refreshDirty() {
    if (!state.model) return;
    const request = Core.collectRequest(state.model, state.baseline, state.draft);
    const dirty = request.edits.length + request.styles.length + request.tables.length + (request.structure || []).length > 0;
    if (dirty !== state.dirty) {
      state.dirty = dirty;
      bridge("dirty", { dirty });
    }
    elements.save.disabled = state.busy || !state.model;
  }

  function refreshHistory() {
    elements.undo.disabled = state.busy || state.undo.length === 0;
    elements.redo.disabled = state.busy || state.redo.length === 0;
  }

  function refreshControls() {
    const paragraph = state.paragraphs.get(state.selectedParagraphId);
    const style = paragraph ? normalizedStyle(paragraph) : null;
    for (const button of [elements.insertBefore, elements.insertAfter, elements.deleteParagraph]) button.disabled = state.busy || !paragraph || !paragraph.editable;
    elements.fontFamily.disabled = state.busy || !paragraph || !paragraph.editable;
    elements.fontSize.disabled = state.busy || !paragraph || !paragraph.editable;
    if (style) {
      ensureFontOption(style.fontFamily);
      elements.fontFamily.value = style.fontFamily;
      elements.fontSize.value = String(number(style.fontSizePt, 10));
    } else {
      elements.fontFamily.value = "";
      elements.fontSize.value = "";
    }
    const table = state.tables.get(state.selectedTableId);
    const dimensions = table ? normalizedTable(table) : null;
    const tableEditable = table && table.editableSize === true;
    elements.tableWidth.disabled = state.busy || !tableEditable;
    elements.tableHeight.disabled = state.busy || !tableEditable;
    elements.tableWidth.value = dimensions ? String(dimensions.widthPt) : "";
    elements.tableHeight.value = dimensions ? String(dimensions.heightPt) : "";
    refreshHistory();
  }

  function ensureFontOption(font) {
    if (!font || Array.from(elements.fontFamily.options).some((option) => option.value === font)) return;
    const option = document.createElement("option");
    option.value = font;
    option.textContent = font;
    elements.fontFamily.appendChild(option);
  }

  function populateFonts() {
    elements.fontFamily.replaceChildren();
    const discovered = Array.isArray(state.model.availableFonts) ? state.model.availableFonts : [];
    const used = Array.from(state.paragraphs.values()).map((node) => normalizedStyle(node).fontFamily);
    const defaults = ["Apple SD Gothic Neo", "AppleMyungjo", "NanumGothic", "NanumMyeongjo"];
    const fonts = Array.from(new Set(discovered.concat(used, defaults).filter(Core.safeFontFamily))).sort((a, b) => a.localeCompare(b, "ko"));
    for (const font of fonts) {
      const option = document.createElement("option");
      option.value = font;
      option.textContent = font;
      elements.fontFamily.appendChild(option);
    }
  }

  function pushHistory(kind, key, coalesce) {
    const now = Date.now();
    if (coalesce && state.lastHistory && state.lastHistory.kind === kind && state.lastHistory.key === key && now - state.lastHistory.time < 700) {
      state.lastHistory.time = now;
      return;
    }
    state.undo.push(Core.snapshotDraft(state.draft));
    if (state.undo.length > LIMITS.history) state.undo.shift();
    state.redo = [];
    state.lastHistory = { kind, key, time: now };
    refreshHistory();
  }

  function syncStructure() {
    const baseline = state.baseline, draft = state.draft;
    state.viewModel = Core.structureModel(state.model, draft.structure || []);
    rebuildIndex(state.viewModel);
    state.baseline = baseline;
    state.draft = draft;
    if (!state.paragraphs.has(state.selectedParagraphId)) {
      state.selectedParagraphId = null;
      closeEditor();
    }
  }

  function changeParagraph(action) {
    if (state.busy || state.composing || !state.selectedParagraphId) return false;
    const id = state.selectedParagraphId;
    const paragraph = state.paragraphs.get(id);
    if (!paragraph || !paragraph.editable) return false;
    const operation = { action, id };
    if (action !== "delete") operation.newId = `new:${crypto.randomUUID()}`;
    const operations = (state.draft.structure || []).concat(operation);
    try { Core.structureModel(state.model, operations); }
    catch (error) { showNotice(error.message, true); return false; }
    pushHistory("structure", id, false);
    state.draft.structure = operations;
    if (action === "delete") {
      delete state.draft.texts[id];
      delete state.draft.styles[id];
    } else {
      state.draft.texts[operation.newId] = "";
      state.draft.styles[operation.newId] = Core.clone(state.draft.styles[id] || {});
    }
    syncStructure();
    render();
    if (operation.newId) selectParagraph(operation.newId, 0);
    refreshDirty();
    refreshControls();
    showNotice(action === "delete" ? "문단을 삭제했습니다. 실행 취소로 복구할 수 있습니다." : "빈 문단을 추가했습니다.", false);
    return true;
  }

  function restoreDraft(snapshot) {
    state.draft = Core.snapshotDraft(snapshot);
    syncStructure();
    state.runCache.clear();
    const selectedText = state.selectedParagraphId ? state.draft.texts[state.selectedParagraphId] : null;
    if (selectedText !== null && selectedText !== undefined) {
      elements.editor.value = selectedText;
      state.selectionStart = Math.min(selectedText.length, state.selectionStart);
      state.selectionEnd = Math.min(selectedText.length, state.selectionEnd);
    }
    state.lastHistory = null;
    refreshDirty();
    scheduleRender();
    refreshControls();
  }

  function undo() {
    if (!state.undo.length || state.busy || state.composing) return false;
    const previous = state.undo.pop();
    state.redo.push(Core.snapshotDraft(state.draft));
    restoreDraft(previous);
    return true;
  }

  function redo() {
    if (!state.redo.length || state.busy || state.composing) return false;
    const next = state.redo.pop();
    state.undo.push(Core.snapshotDraft(state.draft));
    restoreDraft(next);
    return true;
  }

  function commitText(id, text, kind, coalesce) {
    const paragraph = state.paragraphs.get(id);
    if (state.busy || state.composing || !paragraph || !paragraph.editable || typeof text !== "string") return false;
    if (state.draft.texts[id] === text) return true;
    const mapping = Core.mappedRuns(typeof paragraph.text === "string" ? paragraph.text : "", sourceRuns(paragraph), text);
    if (!mapping.ok) {
      const message = mapping.reason === "mapping-budget-exceeded"
        ? "변경 범위가 너무 커 혼합 서식을 안전하게 보존할 수 없습니다. 변경을 나누어 적용해 주세요."
        : mapping.reason === "text-too-long"
          ? "문단은 100,000자까지 편집할 수 있습니다."
          : "문단의 원본 서식 정보를 확인할 수 없어 편집을 적용하지 않았습니다.";
      if (state.selectedParagraphId === id) {
        elements.editor.value = state.draft.texts[id] || "";
        elements.editor.selectionStart = Math.min(elements.editor.value.length, state.selectionStart);
        elements.editor.selectionEnd = Math.min(elements.editor.value.length, state.selectionEnd);
      }
      showNotice(message, true);
      bridge("error", { message });
      return false;
    }
    pushHistory(kind || "text", id, Boolean(coalesce));
    state.draft.texts[id] = text;
    state.runCache.delete(id);
    if (state.selectedParagraphId === id && elements.editor.value !== text) elements.editor.value = text;
    refreshDirty();
    scheduleRender();
    return true;
  }

  function applyParagraphStyle(id, change) {
    const paragraph = state.paragraphs.get(id);
    if (state.busy || state.composing || !paragraph || !paragraph.editable || !change || typeof change !== "object") return false;
    const current = state.draft.styles[id];
    const next = Object.assign({}, current);
    if (Object.prototype.hasOwnProperty.call(change, "fontFamily")) {
      if (!Core.safeFontFamily(change.fontFamily)) return false;
      next.fontFamily = change.fontFamily;
      next.fontFamilyExplicit = true;
    }
    if (Object.prototype.hasOwnProperty.call(change, "fontSizePt")) {
      if (!Core.finiteInRange(change.fontSizePt, LIMITS.fontSizeMin, LIMITS.fontSizeMax)) return false;
      next.fontSizePt = change.fontSizePt;
      next.fontSizePtExplicit = true;
    }
    if (Core.requestsEqual(current, next)) return true;
    pushHistory("style", id, false);
    state.draft.styles[id] = next;
    state.runCache.delete(id);
    refreshDirty();
    scheduleRender();
    refreshControls();
    return true;
  }

  function resizeTable(id, change) {
    const table = state.tables.get(id);
    if (state.busy || state.composing || !table || table.editableSize !== true || !change || typeof change !== "object") return false;
    const current = state.draft.tables[id];
    const next = Object.assign({}, current);
    if (Object.prototype.hasOwnProperty.call(change, "widthPt")) {
      if (!Core.finiteInRange(change.widthPt, LIMITS.tableWidthMin, LIMITS.tableWidthMax)) return false;
      next.widthPt = change.widthPt;
    }
    if (Object.prototype.hasOwnProperty.call(change, "heightPt")) {
      if (!Core.finiteInRange(change.heightPt, LIMITS.tableHeightMin, LIMITS.tableHeightMax)) return false;
      next.heightPt = change.heightPt;
    }
    if (Core.requestsEqual(current, next)) return true;
    pushHistory("table", id, false);
    state.draft.tables[id] = next;
    refreshDirty();
    scheduleRender();
    refreshControls();
    return true;
  }

  function locationText(id) {
    const location = state.locations.get(id) || {};
    const section = number(location.sectionIndex, 0) + 1;
    const pieces = [`구역 ${section}`];
    if (location.tableId) pieces.push(`표 ${location.tableId}`);
    if (location.cellId) pieces.push(`셀 ${location.cellId}`);
    pieces.push(location.label || id);
    return pieces.join(" · ");
  }

  function fragmentForSelection() {
    const fragments = state.fragments[state.selectedParagraphId] || [];
    if (!fragments.length) return null;
    const offset = state.selectionStart;
    return fragments.find((fragment) => offset >= fragment.offset && offset <= fragment.offset + fragment.text.length) || fragments[0];
  }

  function positionEditor() {
    if (!state.selectedParagraphId || elements.editor.hidden) return;
    const fragment = fragmentForSelection();
    if (!fragment || !fragment.dom.isConnected) return;
    const rect = fragment.dom.getBoundingClientRect();
    const viewportTop = document.querySelector(".toolbar").getBoundingClientRect().bottom + (elements.notice.hidden ? 0 : elements.notice.getBoundingClientRect().height);
    const statusTop = document.querySelector(".statusbar").getBoundingClientRect().top;
    const width = Math.max(180, Math.min(rect.width, window.innerWidth - 24));
    elements.editor.style.width = `${width}px`;
    elements.editor.style.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))}px`;
    elements.editor.style.top = `${Math.max(viewportTop + 5, Math.min(rect.top, statusTop - 55))}px`;
    const paragraph = state.paragraphs.get(state.selectedParagraphId);
    applyTextStyle(elements.editor, normalizedStyle(paragraph));
    elements.editor.style.margin = "0";
    elements.editor.style.textIndent = "0";
    elements.editor.style.height = "auto";
    elements.editor.style.height = `${Math.max(38, Math.min(elements.editor.scrollHeight + 4, Math.min(window.innerHeight * .38, 300)))}px`;
  }

  function selectParagraph(id, offset = 0) {
    const paragraph = state.paragraphs.get(id);
    if (state.busy || state.composing || !paragraph) return false;
    const location = state.locations.get(id) || {};
    state.selectedTableId = location.tableId || null;
    if (!paragraph.editable) {
      closeEditor();
      state.selectedParagraphId = id;
      elements.selectionStatus.textContent = `${locationText(id)} · 읽기 전용`;
      showNotice(paragraph.reason || "복합 서식 또는 문서 제어가 포함되어 읽기 전용으로 표시됩니다.", false);
      refreshControls();
      scheduleRender();
      return false;
    }
    const runs = sourceRuns(paragraph).filter((run) => String(run.text || "").length > 0);
    const mixed = new Set(runs.map((run) => JSON.stringify(run.style || {}))).size > 1;
    showNotice(mixed ? "혼합 서식 문단입니다. 입력창은 단일 서식으로 보이지만 미리보기와 저장에는 원래 글자 서식을 보존합니다." : "", false);
    state.selectedParagraphId = id;
    const text = state.draft.texts[id] || "";
    const selectedOffset = Math.max(0, Math.min(text.length, Number.isFinite(offset) ? Math.round(offset) : 0));
    state.selectionStart = selectedOffset;
    state.selectionEnd = selectedOffset;
    elements.editor.value = text;
    elements.editor.hidden = false;
    elements.editor.selectionStart = selectedOffset;
    elements.editor.selectionEnd = selectedOffset;
    elements.selectionStatus.textContent = locationText(id);
    refreshControls();
    scheduleRender();
    requestAnimationFrame(() => { positionEditor(); elements.editor.focus({ preventScroll: true }); });
    return true;
  }

  function closeEditor() {
    if (state.composing) return false;
    if (!elements.editor.hidden) {
      state.selectionStart = elements.editor.selectionStart;
      state.selectionEnd = elements.editor.selectionEnd;
    }
    elements.editor.hidden = true;
    state.composing = false;
    return true;
  }

  function offsetFromPoint(fragment, clientX, clientY) {
    let range = null;
    if (document.caretRangeFromPoint) range = document.caretRangeFromPoint(clientX, clientY);
    else if (document.caretPositionFromPoint) {
      const position = document.caretPositionFromPoint(clientX, clientY);
      if (position) { range = document.createRange(); range.setStart(position.offsetNode, position.offset); }
    }
    if (!range || !fragment.contains(range.startContainer)) return Number(fragment.dataset.start) || 0;
    const prefix = document.createRange();
    prefix.selectNodeContents(fragment);
    prefix.setEnd(range.startContainer, range.startOffset);
    return (Number(fragment.dataset.start) || 0) + prefix.toString().length;
  }

  function selectFirstEditableInCell(cell) {
    const fragments = Array.from(cell.querySelectorAll(".paragraph-fragment"));
    return fragments.find((fragment) => {
      const paragraph = state.paragraphs.get(fragment.dataset.paragraphId);
      return paragraph && paragraph.editable;
    });
  }

  function captureState() {
    if (!state.model) return null;
    return {
      model: Core.clone(state.model),
      baseline: Core.snapshotDraft(state.baseline),
      draft: Core.snapshotDraft(state.draft),
      selectedParagraphId: state.selectedParagraphId,
      selectedTableId: state.selectedTableId,
      selectionStart: state.selectionStart,
      selectionEnd: state.selectionEnd,
      undo: Core.clone(state.undo),
      redo: Core.clone(state.redo),
      dirty: state.dirty,
      zoom: state.zoom
    };
  }

  function restoreState(token) {
    try {
      if (!token || typeof token !== "object" || !token.model) return false;
      Core.validateModel(token.model);
      state.model = Core.clone(token.model);
      rebuildIndex(state.model);
      state.baseline = Core.snapshotDraft(token.baseline);
      state.draft = Core.snapshotDraft(token.draft);
      syncStructure();
      state.selectedParagraphId = state.paragraphs.has(token.selectedParagraphId) ? token.selectedParagraphId : null;
      state.selectedTableId = state.tables.has(token.selectedTableId) ? token.selectedTableId : null;
      state.selectionStart = Math.max(0, number(token.selectionStart, 0));
      state.selectionEnd = Math.max(state.selectionStart, number(token.selectionEnd, state.selectionStart));
      state.undo = Array.isArray(token.undo) ? Core.clone(token.undo).slice(-LIMITS.history) : [];
      state.redo = Array.isArray(token.redo) ? Core.clone(token.redo).slice(-LIMITS.history) : [];
      state.lastHistory = null;
      state.zoom = Math.max(.5, Math.min(1.8, number(token.zoom, 1)));
      state.composing = false;
      populateFonts();
      render();
      if (state.selectedParagraphId) {
        const text = state.draft.texts[state.selectedParagraphId] || "";
        elements.editor.value = text;
        elements.editor.hidden = false;
        elements.editor.selectionStart = Math.min(text.length, state.selectionStart);
        elements.editor.selectionEnd = Math.min(text.length, state.selectionEnd);
      } else closeEditor();
      state.dirty = Boolean(token.dirty);
      bridge("dirty", { dirty: state.dirty });
      refreshControls();
      return true;
    } catch (_) {
      return false;
    }
  }

  function loadDocument(model) {
    const previous = captureState();
    try {
      Core.validateModel(model);
      state.model = Core.clone(model);
      elements.body.classList.toggle("hosted", model.hosted === true);
      state.selectedParagraphId = null;
      state.selectedTableId = null;
      state.selectionStart = 0;
      state.selectionEnd = 0;
      state.composing = false;
      state.dirty = false;
      state.undo = [];
      state.redo = [];
      state.lastHistory = null;
      closeEditor();
      rebuildIndex(state.model);
      state.viewModel = state.model;
      populateFonts();
      showNotice("", false);
      render();
      refreshDirty();
      bridge("dirty", { dirty: false });
      refreshControls();
      bridge("status", { message: `${state.pages.length}쪽 미리보기를 준비했습니다.` });
      return true;
    } catch (error) {
      if (previous) restoreState(previous);
      showNotice(error.message || "문서를 표시하지 못했습니다.", true);
      bridge("error", { message: error.message || String(error) });
      return false;
    }
  }

  function collectChanges() {
    if (!state.model) return null;
    if (state.composing) {
      const message = "한글 조합을 마친 뒤 저장해 주세요.";
      bridge("error", { message });
      throw new Error(message);
    }
    return Core.collectRequest(state.model, state.baseline, state.draft);
  }

  function markSaved() {
    if (!state.model || state.composing) return false;
    state.baseline = Core.snapshotDraft(state.draft);
    state.undo = [];
    state.redo = [];
    state.lastHistory = null;
    refreshDirty();
    refreshControls();
    return true;
  }

  function setBusy(busy) {
    state.busy = Boolean(busy);
    elements.body.classList.toggle("busy", state.busy);
    elements.busyStatus.textContent = state.busy ? "처리 중…" : "";
    elements.save.disabled = state.busy || !state.model;
    elements.fallback.disabled = state.busy;
    refreshControls();
    return true;
  }

  function getDiagnostics() {
    const canonicalText = {};
    for (const [id] of state.paragraphs) canonicalText[id] = state.draft.texts[id];
    const rendered = {};
    for (const id of Object.keys(state.fragments)) {
      rendered[id] = state.fragments[id]
        .slice()
        .sort((a, b) => a.offset - b.offset)
        .map((fragment) => ({ offset: fragment.offset, text: fragment.text }));
    }
    return {
      pages: state.pages.length,
      canonicalText,
      rendered,
      renderedTextFragments: rendered,
      selection: state.selectedParagraphId ? { id: state.selectedParagraphId, start: state.selectionStart, end: state.selectionEnd } : null,
      composing: state.composing,
      layout: {
        renderOperations: state.renderOperations,
        repeatingRegions: repeatingDiagnostics(),
        images: Array.from(elements.pages.querySelectorAll("img.document-image")).map((image) => {
          const page = state.pages.find((item) => item.sheet.contains(image));
          const bounds = page ? (image.closest(".repeating-region") ? page.sheet : page.content).getBoundingClientRect() : null;
          return imageDiagnostic(image, bounds);
        }),
        pages: state.pages.map((page) => ({
          contentClientHeight: page.content.clientHeight,
          contentScrollHeight: page.content.scrollHeight,
          contentHeightPx: page.contentHeightPx,
          contentWidthPx: page.contentWidthPx,
          overheight: page.overheight
        }))
      },
      warnings: state.warnings.map(({ code, message, id }) => ({ code, message, id })),
      dirty: state.dirty
    };
  }

  function setParagraphText(id, text) {
    const changed = commitText(id, text, "set-text", false);
    if (changed && state.selectedParagraphId === id) {
      state.selectionStart = String(text).length;
      state.selectionEnd = state.selectionStart;
      elements.editor.selectionStart = state.selectionStart;
      elements.editor.selectionEnd = state.selectionEnd;
    }
    return changed;
  }

  elements.pages.addEventListener("click", (event) => {
    if (state.composing) {
      event.preventDefault();
      return;
    }
    const fragment = event.target.closest(".paragraph-fragment");
    if (fragment) {
      const offset = offsetFromPoint(fragment, event.clientX, event.clientY);
      selectParagraph(fragment.dataset.paragraphId, offset);
      return;
    }
    const cell = event.target.closest("td[data-cell-id]");
    if (cell) {
      const table = cell.closest("table[data-table-id]");
      state.selectedTableId = table ? table.dataset.tableId : null;
      const first = selectFirstEditableInCell(cell);
      if (first) selectParagraph(first.dataset.paragraphId, Number(first.dataset.start) || 0);
      else {
        elements.selectionStatus.textContent = `표 ${state.selectedTableId || ""} · 셀 ${cell.dataset.cellId || ""} · 읽기 전용`;
        refreshControls();
        scheduleRender();
      }
      return;
    }
    const table = event.target.closest("table[data-table-id]");
    if (table) {
      state.selectedTableId = table.dataset.tableId;
      refreshControls();
      scheduleRender();
    }
  });

  elements.editor.addEventListener("compositionstart", () => { state.composing = true; });
  elements.editor.addEventListener("compositionend", () => {
    const nextStart = elements.editor.selectionStart;
    const nextEnd = elements.editor.selectionEnd;
    state.composing = false;
    if (commitText(state.selectedParagraphId, elements.editor.value, "composition", false)) {
      state.selectionStart = nextStart;
      state.selectionEnd = nextEnd;
    }
  });
  elements.editor.addEventListener("input", () => {
    if (state.busy) {
      elements.editor.value = state.draft.texts[state.selectedParagraphId] || "";
      return;
    }
    const nextStart = elements.editor.selectionStart;
    const nextEnd = elements.editor.selectionEnd;
    if (!state.composing && commitText(state.selectedParagraphId, elements.editor.value, "text", true)) {
      state.selectionStart = nextStart;
      state.selectionEnd = nextEnd;
    }
    positionEditor();
  });
  elements.editor.addEventListener("select", () => {
    state.selectionStart = elements.editor.selectionStart;
    state.selectionEnd = elements.editor.selectionEnd;
  });
  elements.editor.addEventListener("paste", (event) => {
    event.preventDefault();
    if (state.busy || state.composing) return;
    const plainText = event.clipboardData ? event.clipboardData.getData("text/plain") : "";
    elements.editor.setRangeText(plainText, elements.editor.selectionStart, elements.editor.selectionEnd, "end");
    const nextStart = elements.editor.selectionStart;
    const nextEnd = elements.editor.selectionEnd;
    if (commitText(state.selectedParagraphId, elements.editor.value, "paste", false)) {
      state.selectionStart = nextStart;
      state.selectionEnd = nextEnd;
    }
    positionEditor();
  });
  elements.editor.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      if (!state.composing) closeEditor();
    }
  });

  document.addEventListener("keydown", (event) => {
    const command = event.metaKey || event.ctrlKey;
    if (command && event.key.toLowerCase() === "s") {
      event.preventDefault();
      if (state.composing) {
        showNotice("한글 조합을 마친 뒤 저장해 주세요.", false);
      } else if (!state.busy && state.model) bridge("save");
      return;
    }
    if (!command || event.key.toLowerCase() !== "z") return;
    event.preventDefault();
    if (event.shiftKey) redo(); else undo();
  });

  elements.insertBefore.addEventListener("click", () => changeParagraph("insertBefore"));
  elements.insertAfter.addEventListener("click", () => changeParagraph("insertAfter"));
  elements.deleteParagraph.addEventListener("click", () => changeParagraph("delete"));
  elements.editor.addEventListener("keydown", event => {
    if ((event.key === "Backspace" || event.key === "Delete") && elements.editor.value === ""
        && !event.isComposing && !state.composing && !state.busy) {
      if (changeParagraph("delete")) event.preventDefault();
    }
  });
  elements.undo.addEventListener("click", undo);
  elements.redo.addEventListener("click", redo);
  elements.fontFamily.addEventListener("change", () => applyParagraphStyle(state.selectedParagraphId, { fontFamily: elements.fontFamily.value }));
  elements.fontSize.addEventListener("change", () => applyParagraphStyle(state.selectedParagraphId, { fontSizePt: Number(elements.fontSize.value) }));
  elements.tableWidth.addEventListener("change", () => resizeTable(state.selectedTableId, { widthPt: Number(elements.tableWidth.value) }));
  elements.tableHeight.addEventListener("change", () => resizeTable(state.selectedTableId, { heightPt: Number(elements.tableHeight.value) }));
  elements.save.addEventListener("click", () => { if (!state.busy && state.model) bridge("save"); });
  elements.fallback.addEventListener("click", () => { if (!state.busy) bridge("fallback"); });
  elements.zoomOut.addEventListener("click", () => setZoom(state.zoom - .1));
  elements.zoomIn.addEventListener("click", () => setZoom(state.zoom + .1));
  elements.viewport.addEventListener("scroll", () => requestAnimationFrame(positionEditor), { passive: true });
  window.addEventListener("resize", () => requestAnimationFrame(positionEditor));

  function setZoom(value) {
    state.zoom = Math.max(.5, Math.min(1.8, Math.round(value * 10) / 10));
    elements.zoomValue.textContent = `${Math.round(state.zoom * 100)}%`;
    scheduleRender();
  }

  elements.editor.addEventListener("beforeinput", (event) => {
    if (state.busy) event.preventDefault();
  });

  window.studio = Object.freeze({
    loadDocument,
    collectChanges,
    markSaved,
    setBusy,
    getDiagnostics,
    selectParagraph,
    setParagraphText,
    applyParagraphStyle,
    resizeTable,
    changeParagraph,
    undo,
    redo,
    captureState,
    restoreState,
    flushRender,
    preparePDFSnapshot
  });

  bridge("ready");
})();
