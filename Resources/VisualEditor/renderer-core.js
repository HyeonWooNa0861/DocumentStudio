(function (root) {
  "use strict";

  const LIMITS = Object.freeze({
    fontSizeMin: 6,
    fontSizeMax: 96,
    tableWidthMin: 36,
    tableWidthMax: 1440,
    tableHeightMin: 1,
    tableHeightMax: 2880,
    textLengthMax: 100000,
    runMappingCells: 1000000,
    history: 100
  });

  function clone(value) {
    if (value === undefined) return undefined;
    return JSON.parse(JSON.stringify(value));
  }

  function finiteInRange(value, min, max) {
    return typeof value === "number" && Number.isFinite(value) && value >= min && value <= max;
  }

  function safeFontFamily(value) {
    return typeof value === "string" && value.length >= 1 && value.length <= 128 && !/[\u0000-\u001f\u007f]/u.test(value);
  }

  function graphemeBoundaries(text) {
    const value = String(text || "");
    const boundaries = [0];
    if (typeof Intl !== "undefined" && typeof Intl.Segmenter === "function") {
      const segmenter = new Intl.Segmenter("ko", { granularity: "grapheme" });
      for (const part of segmenter.segment(value)) {
        const end = part.index + part.segment.length;
        if (end > boundaries[boundaries.length - 1]) boundaries.push(end);
      }
    } else {
      let offset = 0;
      for (const character of Array.from(value)) {
        offset += character.length;
        boundaries.push(offset);
      }
    }
    if (boundaries[boundaries.length - 1] !== value.length) boundaries.push(value.length);
    return boundaries;
  }

  function baselineCodePointStyles(text, runs) {
    const expected = String(text || "");
    const sourceRuns = Array.isArray(runs) ? runs : [];
    if (sourceRuns.map((run) => String(run && run.text || "")).join("") !== expected) return null;
    const styles = [];
    const runIndices = [];
    sourceRuns.forEach((run, runIndex) => {
      const style = clone(run && run.style || {});
      for (const _character of Array.from(String(run && run.text || ""))) {
        styles.push(style);
        runIndices.push(runIndex);
      }
    });
    return { styles, runIndices };
  }

  function mappedRuns(baselineText, baselineRuns, currentText) {
    if (String(currentText || "").length > LIMITS.textLengthMax) {
      return { ok: false, reason: "text-too-long", runs: [] };
    }
    const original = Array.from(String(baselineText || ""));
    const current = Array.from(String(currentText || ""));
    const sourceRuns = Array.isArray(baselineRuns) ? baselineRuns : [];
    const baseline = baselineCodePointStyles(baselineText, baselineRuns);
    if (!baseline || baseline.styles.length !== original.length) {
      return { ok: false, reason: "baseline-runs-mismatch", runs: [] };
    }
    const originalStyles = baseline.styles;
    const originalRunIndices = baseline.runIndices;
    if (original.length === 0 && sourceRuns.length > 0) {
      const runs = sourceRuns.map((run, index) => ({
        text: index === 0 ? current.join("") : "",
        style: clone(run && run.style || {})
      }));
      return { ok: true, reason: null, runs };
    }
    if (sourceRuns.length <= 1) {
      return {
        ok: true,
        reason: null,
        runs: sourceRuns.length ? [{ text: current.join(""), style: clone(sourceRuns[0] && sourceRuns[0].style || {}) }]
          : (current.length ? [{ text: current.join(""), style: {} }] : [])
      };
    }

    let prefix = 0;
    while (prefix < original.length && prefix < current.length && original[prefix] === current[prefix]) prefix += 1;
    let suffix = 0;
    while (suffix < original.length - prefix && suffix < current.length - prefix &&
           original[original.length - 1 - suffix] === current[current.length - 1 - suffix]) suffix += 1;
    const oldInterior = original.slice(prefix, original.length - suffix);
    const newInterior = current.slice(prefix, current.length - suffix);
    if (oldInterior.length + 1 > Math.floor(LIMITS.runMappingCells / (newInterior.length + 1))) {
      return { ok: false, reason: "mapping-budget-exceeded", runs: [] };
    }

    const columns = newInterior.length + 1;
    const scores = new Uint32Array((oldInterior.length + 1) * columns);
    for (let oldIndex = oldInterior.length - 1; oldIndex >= 0; oldIndex -= 1) {
      for (let newIndex = newInterior.length - 1; newIndex >= 0; newIndex -= 1) {
        const here = oldIndex * columns + newIndex;
        scores[here] = oldInterior[oldIndex] === newInterior[newIndex]
          ? 1 + scores[(oldIndex + 1) * columns + newIndex + 1]
          : Math.max(scores[(oldIndex + 1) * columns + newIndex], scores[oldIndex * columns + newIndex + 1]);
      }
    }

    const output = [];
    const append = (character, style, runIndex) => output.push({ character, style: style || {}, runIndex });
    for (let index = 0; index < prefix; index += 1) append(original[index], originalStyles[index], originalRunIndices[index]);
    let oldIndex = 0;
    let newIndex = 0;
    while (oldIndex < oldInterior.length || newIndex < newInterior.length) {
      if (oldIndex < oldInterior.length && newIndex < newInterior.length && oldInterior[oldIndex] === newInterior[newIndex]) {
        append(oldInterior[oldIndex], originalStyles[prefix + oldIndex], originalRunIndices[prefix + oldIndex]);
        oldIndex += 1;
        newIndex += 1;
        continue;
      }
      const removedStart = oldIndex;
      const insertedStart = newIndex;
      while (oldIndex < oldInterior.length || newIndex < newInterior.length) {
        if (oldIndex < oldInterior.length && newIndex < newInterior.length && oldInterior[oldIndex] === newInterior[newIndex]) break;
        const deleteScore = oldIndex < oldInterior.length ? scores[(oldIndex + 1) * columns + newIndex] : -1;
        const insertScore = newIndex < newInterior.length ? scores[oldIndex * columns + newIndex + 1] : -1;
        if (oldIndex < oldInterior.length && (newIndex >= newInterior.length || deleteScore >= insertScore)) oldIndex += 1;
        else newIndex += 1;
      }
      let inherited;
      let inheritedRunIndex;
      if (removedStart < oldIndex) {
        inherited = originalStyles[prefix + removedStart];
        inheritedRunIndex = originalRunIndices[prefix + removedStart];
      } else if (prefix + removedStart > 0) {
        inherited = originalStyles[prefix + removedStart - 1];
        inheritedRunIndex = originalRunIndices[prefix + removedStart - 1];
      } else {
        inherited = originalStyles[0] || {};
        inheritedRunIndex = originalRunIndices[0] === undefined ? 0 : originalRunIndices[0];
      }
      for (let index = insertedStart; index < newIndex; index += 1) append(newInterior[index], inherited, inheritedRunIndex);
    }
    for (let index = original.length - suffix; index < original.length; index += 1) {
      append(original[index], originalStyles[index], originalRunIndices[index]);
    }

    const runs = sourceRuns.map((run) => ({ text: "", style: clone(run && run.style || {}) }));
    for (const item of output) {
      if (!runs[item.runIndex]) runs[item.runIndex] = { text: "", style: clone(item.style) };
      runs[item.runIndex].text += item.character;
    }
    return { ok: true, reason: null, runs };
  }

  function validateModel(model) {
    if (!model || typeof model !== "object") throw new Error("문서 모델이 올바르지 않습니다.");
    if (model.schemaVersion !== 1) throw new Error("지원하지 않는 문서 모델 버전입니다.");
    if (typeof model.sourceSha256 !== "string" || !/^[0-9a-f]{64}$/iu.test(model.sourceSha256)) {
      throw new Error("원본 문서 지문이 올바르지 않습니다.");
    }
    if (!Array.isArray(model.sections)) throw new Error("문서 구역 정보가 없습니다.");
    return true;
  }

  function snapshotDraft(draft) {
    return {
      texts: clone(draft.texts || {}),
      styles: clone(draft.styles || {}),
      tables: clone(draft.tables || {}),
      structure: clone(draft.structure || [])
    };
  }

  function requestsEqual(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
  }

  function collectRequest(model, baseline, draft) {
    const edits = [];
    const styles = [];
    const tables = [];
    const ids = Object.keys(draft.texts || {}).sort();
    for (const id of ids) {
      if (draft.texts[id] !== baseline.texts[id]) edits.push({ id, text: draft.texts[id] });
    }
    for (const id of Object.keys(draft.styles || {}).sort()) {
      const current = draft.styles[id] || {};
      const original = baseline.styles[id] || {};
      const change = { id };
      if (current.fontFamily !== original.fontFamily || current.fontFamilyExplicit !== original.fontFamilyExplicit) change.fontFamily = current.fontFamily;
      if (current.fontSizePt !== original.fontSizePt || current.fontSizePtExplicit !== original.fontSizePtExplicit) change.fontSizePt = current.fontSizePt;
      if (Object.keys(change).length > 1) styles.push(change);
    }
    for (const id of Object.keys(draft.tables || {}).sort()) {
      const current = draft.tables[id] || {};
      const original = baseline.tables[id] || {};
      const change = { id };
      if (current.widthPt !== original.widthPt) change.widthPt = current.widthPt;
      if (current.heightPt !== original.heightPt) change.heightPt = current.heightPt;
      if (Object.keys(change).length > 1) tables.push(change);
    }
    const request = {
      schemaVersion: model.schemaVersion,
      sourceSha256: model.sourceSha256,
      edits,
      styles,
      tables
    };
    if (!requestsEqual(draft.structure || [], baseline.structure || [])) request.structure = clone(draft.structure || []);
    return request;
  }

  function structureModel(model, operations) {
    const result = clone(model);
    const locate = (value, id) => {
      if (!value || typeof value !== "object") return null;
      if (Array.isArray(value)) {
        const index = value.findIndex(node => node && node.type === "paragraph" && node.id === id);
        if (index >= 0) return { list: value, index, node: value[index] };
        for (const child of value) { const found = locate(child, id); if (found) return found; }
      } else {
        for (const child of Object.values(value)) { const found = locate(child, id); if (found) return found; }
      }
      return null;
    };
    for (const operation of operations || []) {
      const anchor = locate(result.sections, operation.id);
      if (!anchor || !anchor.node.editable) throw new Error("편집 가능한 문단을 선택해 주세요.");
      if (operation.action === "delete") {
        if (anchor.list.filter(node => node.type === "paragraph" || node.anchorParagraphId).length <= 1)
          throw new Error("구역 또는 표 셀에는 문단이 하나 이상 필요합니다.");
        anchor.list.splice(anchor.index, 1);
      } else if (["insertBefore", "insertAfter"].includes(operation.action)) {
        if (locate(result.sections, operation.newId)) throw new Error("중복 문단 ID입니다.");
        const node = clone(anchor.node);
        node.id = operation.newId;
        node.label = "새 문단";
        node.text = "";
        node.runs = (node.runs || []).map(run => Object.assign({}, run, { text: "" }));
        node.pageBreakBefore = false;
        node.objectAnchor = false;
        anchor.list.splice(anchor.index + (operation.action === "insertAfter" ? 1 : 0), 0, node);
      } else throw new Error("지원하지 않는 문단 작업입니다.");
    }
    return result;
  }

  root.StudioCore = Object.freeze({
    LIMITS,
    structureModel,
    clone,
    finiteInRange,
    safeFontFamily,
    graphemeBoundaries,
    mappedRuns,
    validateModel,
    snapshotDraft,
    requestsEqual,
    collectRequest
  });
})(typeof globalThis !== "undefined" ? globalThis : this);
