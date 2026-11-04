package local.nahw.hwpxstudio;

import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.w3c.dom.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Local, bounded OOXML import. No external relationships are fetched. */
final class DocxConverter {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String HP = "http://www.hancom.co.kr/hwpml/2011/paragraph";
    private static final String HH = "http://www.hancom.co.kr/hwpml/2011/head";
    private static final String HC = "http://www.hancom.co.kr/hwpml/2011/core";
    private static final String OPF = "http://www.idpf.org/2007/opf/";
    private final LinkedHashMap<String, byte[]> source;
    private final LinkedHashMap<String, byte[]> target;
    private final LinkedHashSet<String> warnings = new LinkedHashSet<String>();
    private final Map<String, Element> styles = new HashMap<String, Element>();
    private final Map<String, String> charIds = new HashMap<String, String>();
    private final Map<String, String> paraIds = new HashMap<String, String>();
    private final Map<String, String> borderIds = new HashMap<String, String>();
    private final Map<String, String> images = new HashMap<String, String>();
    private final Map<String, Integer> listCounters = new HashMap<String, Integer>();
    private final Document document, header, section, content;
    private Document numbering;
    private Element defaultR, defaultP, charBase, paraBase, secPr, columns;
    private Map<String, Element> relationships;
    private String part = "word/document.xml", defaultStyle = "";
    private int nextId = 10000;
    private long bodyWidth = 42520;

    static Map<String, Object> convert(Path input, Path output) throws Exception {
        HwpxArchive.requireReadableFile(input, "DOCX 파일");
        HwpxArchive.ensureDistinctInputOutput(input, output);
        if (Files.size(input) > HwpxArchive.MAX_ARCHIVE_BYTES) throw new EngineException("DOCX 파일이 허용 크기(512MB)를 초과합니다.");
        DocxConverter converter = new DocxConverter(readZip(Files.newInputStream(input)));
        byte[] bytes = converter.build();
        HwpxArchive.writeGeneratedAtomically(bytes, output);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("output", output.toAbsolutePath().normalize().toString());
        result.put("warnings", new ArrayList<String>(converter.warnings));
        return result;
    }

    private DocxConverter(LinkedHashMap<String, byte[]> input) throws Exception {
        source = input;
        if (!source.containsKey("[Content_Types].xml") || !source.containsKey(part))
            throw new EngineException("올바른 DOCX 문서가 아닙니다. 암호화된 파일은 암호를 해제한 복사본을 사용하세요.");
        Document types = xml(source, "[Content_Types].xml");
        for (Element e : descendants(types.getDocumentElement(), "*", "Override")) {
            String type = e.getAttribute("ContentType").toLowerCase(Locale.ROOT);
            if (type.contains("macroenabled") || type.contains("vbaproject")) throw new EngineException("매크로가 포함된 Word 문서는 변환하지 않습니다. DOCX 복사본을 사용하세요.");
        }
        for (String name : source.keySet()) if (name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))
            throw new EngineException("매크로가 포함된 Word 문서는 변환하지 않습니다.");
        document = xml(source, part);
        if (!W.equals(document.getDocumentElement().getNamespaceURI()) || child(document.getDocumentElement(), W, "body") == null)
            throw new EngineException("지원하는 Word DOCX 본문을 찾을 수 없습니다. Word의 일반 DOCX 형식으로 저장하세요.");
        target = readZip(new ByteArrayInputStream(HWPXWriter.toBytes(BlankFileMaker.make())));
        header = xml(target, "Contents/header.xml"); section = xml(target, "Contents/section0.xml"); content = xml(target, "Contents/content.hpf");
        charBase = (Element) first(header, HH, "charPr").cloneNode(true);
        paraBase = (Element) first(header, HH, "paraPr").cloneNode(true);
        secPr = (Element) first(section, HP, "secPr").cloneNode(true);
        columns = (Element) first(section, HP, "ctrl").cloneNode(true);
        Element root = section.getDocumentElement(); while (root.hasChildNodes()) root.removeChild(root.getFirstChild());
        relationships = rels(part);
        if (source.containsKey("word/styles.xml")) {
            Document styleDoc = xml(source, "word/styles.xml");
            defaultR = first(styleDoc, W, "rPrDefault"); defaultR = child(defaultR, W, "rPr");
            defaultP = first(styleDoc, W, "pPrDefault"); defaultP = child(defaultP, W, "pPr");
            for (Element e : descendants(styleDoc.getDocumentElement(), W, "style")) {
                styles.put(attr(e, "styleId"), e);
                if ("paragraph".equals(attr(e, "type")) && "1".equals(attr(e, "default"))) defaultStyle = attr(e, "styleId");
            }
        }
        if (source.containsKey("word/numbering.xml")) numbering = xml(source, "word/numbering.xml");
        warnings.add("DOCX의 문단·표·그림과 기본 서식을 변환했습니다. 글꼴 대체와 줄바꿈·쪽 배치는 달라질 수 있으므로 최종 서식은 한컴독스에서 확인하세요.");
    }

    private byte[] build() throws Exception {
        Element body = child(document.getDocumentElement(), W, "body");
        List<Element> sections = descendants(body, W, "sectPr");
        if (!sections.isEmpty()) page(sections.get(sections.size() - 1));
        if (sections.size() > 1) warnings.add("여러 구역의 용지·여백·머리말은 마지막 구역 기준으로 통합했습니다. 구역 나눔 위치는 별도 검수가 필요합니다.");
        blocks(body, section.getDocumentElement(), bodyWidth, 0);
        if (children(section.getDocumentElement(), HP, "p").isEmpty()) section.getDocumentElement().appendChild(emptyParagraph());
        Element firstRun = child(children(section.getDocumentElement(), HP, "p").get(0), HP, "run");
        firstRun.insertBefore(section.importNode(columns, true), firstRun.getFirstChild());
        firstRun.insertBefore(section.importNode(secPr, true), firstRun.getFirstChild());
        runningContent(sections.isEmpty() ? null : sections.get(sections.size()-1), firstRun);
        unsupported(body);
        target.put("Contents/header.xml", HwpxArchive.serializeXml(header));
        target.put("Contents/section0.xml", HwpxArchive.serializeXml(section));
        target.put("Contents/content.hpf", HwpxArchive.serializeXml(content));
        StringBuilder preview = new StringBuilder();
        for (Element t : descendants(section.getDocumentElement(), HP, "t")) preview.append(t.getTextContent()).append('\n');
        target.put("Preview/PrvText.txt", preview.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> item : target.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                if ("mimetype".equals(item.getKey())) {
                    CRC32 crc = new CRC32(); crc.update(item.getValue()); entry.setMethod(ZipEntry.STORED);
                    entry.setSize(item.getValue().length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private void page(Element sourceSection) throws EngineException {
        Element pg = child(secPr, HP, "pagePr"), size = child(sourceSection, W, "pgSz"), mar = child(sourceSection, W, "pgMar");
        long width = number(attr(size, "w"), 11906) * 5, height = number(attr(size, "h"), 16838) * 5;
        if (width < 1000 || height < 1000 || width > 1000000 || height > 1000000) throw new EngineException("DOCX 용지 크기가 허용 범위를 벗어납니다.");
        attrs(pg, "width", width, "height", height, "landscape", "WIDELY");
        Element margin = child(pg, HP, "margin");
        for (String key : new String[]{"left","right","top","bottom","header","footer","gutter"})
            margin.setAttribute(key, String.valueOf(Math.max(0, number(attr(mar, key), key.equals("header") || key.equals("footer") ? 720 : key.equals("gutter") ? 0 : 1440)) * 5));
        bodyWidth = width - number(margin.getAttribute("left"), 0) - number(margin.getAttribute("right"), 0);
        if (bodyWidth < 1000) throw new EngineException("DOCX 본문 너비가 너무 작습니다.");
        Element cols = child(sourceSection, W, "cols");
        if (number(attr(cols, "num"), 1) > 1) warnings.add("다단 배치는 단일 본문 열로 변환했습니다.");
    }

    private void blocks(Element from, Element to, long width, int depth) throws Exception {
        if (depth > 32) throw new EngineException("DOCX 문서의 중첩 깊이가 허용 범위를 초과합니다.");
        for (Element e : elements(from)) {
            if (is(e, W, "p")) to.appendChild(paragraph(e, width, depth));
            else if (is(e, W, "tbl")) {
                Element p = emptyParagraph(); child(p, HP, "run").appendChild(table(e, width, depth+1)); to.appendChild(p);
            } else if (is(e, W, "sdt")) blocks(child(e,W,"sdtContent"), to,width,depth+1);
            else if (is(e,W,"customXml") || is(e,W,"ins")) blocks(e,to,width,depth+1);
            else if (is(e,W,"altChunk")) throw new EngineException("외부 문서 삽입(altChunk)이 있는 DOCX는 변환할 수 없습니다. Word에서 본문으로 병합한 복사본을 사용하세요.");
            else if (!is(e,W,"sectPr") && !is(e,W,"del") && hasText(e)) warnings.add("지원하지 않는 본문 구성 요소가 있습니다: " + e.getLocalName());
        }
    }

    private Element paragraph(Element p, long width, int depth) throws Exception {
        Element pPr = child(p,W,"pPr"); String style = val(child(pPr,W,"pStyle")); if (style.isEmpty()) style=defaultStyle;
        LinkedHashMap<String,Element> pp = properties(defaultP,style,"pPr",pPr);
        Element hp = emptyParagraph(); hp.setAttribute("paraPrIDRef", paragraphStyle(pp));
        if (on(pp.get("pageBreakBefore"))) hp.setAttribute("pageBreak","1");
        Element run = child(hp,HP,"run"); String label = listLabel(pp.get("numPr"));
        if (!label.isEmpty()) addText(run,label);
        inline(p,hp,style,width,depth);
        if (!run.hasChildNodes()) hp.removeChild(run);
        if (children(hp,HP,"run").isEmpty()) hp.appendChild(newRun("0"));
        return hp;
    }

    private void inline(Element parent, Element hp, String paragraphStyle, long width, int depth) throws Exception {
        if (depth > 48) throw new EngineException("DOCX 문서의 중첩 깊이가 허용 범위를 초과합니다.");
        for (Element e : elements(parent)) {
            if (is(e,W,"r")) {
                Element rp=child(e,W,"rPr");
                LinkedHashMap<String,Element> props=properties(defaultR,paragraphStyle,"rPr",null);
                String cs=val(child(rp,W,"rStyle")); mergeStyle(props,cs,"rPr",new HashSet<String>()); merge(props,rp);
                Element run=newRun(characterStyle(props)); hp.appendChild(run);
                for (Element node : elements(e)) {
                    if (is(node,W,"t")) addText(run,node.getTextContent());
                    else if (is(node,W,"tab")) addText(run,"\t");
                    else if (is(node,W,"br") || is(node,W,"cr")) {
                        if ("page".equals(attr(node,"type"))) {
                            hp.setAttribute("pageBreak","1");
                            if (hasText(e)) warnings.add("문단 내부 쪽 나눔은 해당 문단 앞 쪽 나눔으로 변환했습니다.");
                        } else if ("column".equals(attr(node,"type"))) { addText(run,"\n"); warnings.add("열 나눔은 줄바꿈으로 변환했습니다."); }
                        else addText(run,"\n");
                    } else if (is(node,W,"drawing") || is(node,W,"pict")) picture(node,run,width);
                    else if (is(node,W,"noBreakHyphen")) addText(run,"\u2011");
                    else if (is(node,W,"softHyphen")) addText(run,"\u00ad");
                    else if (is(node,W,"sym")) { try { addText(run,String.valueOf((char)Integer.parseInt(attr(node,"char"),16))); } catch(Exception ignored) { warnings.add("해석하지 못한 기호 문자가 있습니다."); } }
                    else if (is(node,W,"instrText") || is(node,W,"fldChar")) warnings.add("Word 필드는 표시된 결과 글자로 변환했습니다. 자동 갱신되지 않습니다.");
                    else if (is(node,W,"footnoteReference") || is(node,W,"endnoteReference")) throw new EngineException("각주·미주가 있는 DOCX는 아직 지원하지 않습니다. 내용 누락 방지를 위해 변환을 중지했습니다.");
                    else if (!is(node,W,"rPr") && !is(node,W,"lastRenderedPageBreak")) warnings.add("지원하지 않는 Word 실행 요소가 있습니다: " + node.getLocalName());
                }
            } else if (is(e,W,"hyperlink")) {
                inline(e,hp,paragraphStyle,width,depth+1);
                String id=e.getAttributeNS(R,"id"); Element rel=relationships.get(id);
                if (rel!=null && !rel.getAttribute("Target").isEmpty()) {
                    String url=rel.getAttribute("Target");
                    // Keeping destinations as visible text survives basic HWPX text editing.
                    if (!e.getTextContent().contains(url)) { Element run=newRun("0"); addText(run," ("+url+")"); hp.appendChild(run); }
                    warnings.add("하이퍼링크는 표시 글자와 주소를 보존했습니다. 클릭 가능한 링크 대신 일반 텍스트입니다.");
                } else if (!attr(e,"anchor").isEmpty()) warnings.add("문서 내부 링크는 표시 글자만 보존했습니다.");
            } else if (is(e,W,"sdt")) inline(child(e,W,"sdtContent"),hp,paragraphStyle,width,depth+1);
            else if (is(e,W,"ins") || is(e,W,"smartTag") || is(e,W,"customXml") || is(e,W,"fldSimple")) {
                if (is(e,W,"fldSimple") && "PAGE".equals(attr(e,"instr").trim())) {
                    Element fieldRun=newRun("0"), ctrl=el("ctrl"), auto=el("autoNum","num",1,"numType","PAGE");
                    auto.appendChild(el("autoNumFormat","type","DIGIT","userChar","","prefixChar","","suffixChar","","supscript",0));
                    ctrl.appendChild(auto);fieldRun.appendChild(ctrl);hp.appendChild(fieldRun);
                    warnings.add("쪽 번호 PAGE 필드는 HWPX 자동 번호로 보존했습니다. Studio 미리보기·PDF에는 원본 쪽 번호가 표시되지 않으므로 한컴독스에서 확인하세요.");
                    continue;
                }
                if (is(e,W,"fldSimple")) warnings.add("Word 필드는 표시된 결과 글자로 변환했습니다. 자동 갱신되지 않습니다.");
                inline(e,hp,paragraphStyle,width,depth+1);
            } else if ("AlternateContent".equals(e.getLocalName())) {
                Element choice=child(e,"*","Choice"), fallback=child(e,"*","Fallback");
                inline(fallback!=null ? fallback : choice,hp,paragraphStyle,width,depth+1);
                warnings.add("Word 대체 콘텐츠는 한 가지 표현으로 변환했습니다.");
            } else if (is(e,W,"del")) warnings.add("변경 추적은 최종 표시 기준으로 변환했습니다. 삭제된 내용은 제외했습니다.");
            else if ("oMath".equals(e.getLocalName()) || "oMathPara".equals(e.getLocalName())) {
                Element run=newRun("0"); addText(run,e.getTextContent()); hp.appendChild(run); warnings.add("수식(math)은 일반 글자로 변환했습니다. 수식 배치는 보존되지 않습니다.");
            }
        }
    }

    private String characterStyle(Map<String,Element> props) throws EngineException {
        String key=propertyKey(props); if(charIds.containsKey(key)) return charIds.get(key);
        Element c=(Element)charBase.cloneNode(true); String id=String.valueOf(100+charIds.size()); c.setAttribute("id",id);
        long size=number(val(props.get("sz")),22)*50; c.setAttribute("height",String.valueOf(Math.max(100,Math.min(40000,size))));
        String color=val(props.get("color")); if(color.matches("[0-9a-fA-F]{6}")) c.setAttribute("textColor","#"+color.toUpperCase(Locale.ROOT));
        for(String flag:new String[]{"bold","italic"}) { removeChildren(c,HH,flag); if(on(props.get(flag.equals("bold")?"b":"i"))) c.appendChild(header.createElementNS(HH,"hh:"+flag)); }
        Element u=child(c,HH,"underline"); String uv=val(props.get("u")); attrs(u,"type",(!uv.isEmpty()&&!uv.equals("none")&&!uv.equals("0"))?"BOTTOM":"NONE");
        if(on(props.get("strike"))) attrs(child(c,HH,"strikeout"),"shape","SOLID");
        Element font=props.get("rFonts"); String face=attr(font,"eastAsia"); if(face.isEmpty()) face=attr(font,"ascii"); if(face.isEmpty()) face=attr(font,"hAnsi");
        if(!face.isEmpty()) {
            for(Element ff:descendants(header.getDocumentElement(),HH,"fontface")) {
                String fid=null; List<Element> fonts=children(ff,HH,"font");
                for(Element f:fonts) if(face.equals(f.getAttribute("face"))) fid=f.getAttribute("id");
                if(fid==null) { Element f=(Element)fonts.get(0).cloneNode(true); fid=String.valueOf(fonts.size()); attrs(f,"id",fid,"face",face); ff.appendChild(f); ff.setAttribute("fontCnt",String.valueOf(fonts.size()+1)); }
                String lang=ff.getAttribute("lang").toLowerCase(Locale.ROOT); child(c,HH,"fontRef").setAttribute(lang,fid);
            }
        }
        Element chars=first(header,HH,"charProperties"); chars.appendChild(c); chars.setAttribute("itemCnt",String.valueOf(children(chars,HH,"charPr").size())); charIds.put(key,id); return id;
    }

    private String paragraphStyle(Map<String,Element> props) throws EngineException {
        String key=propertyKey(props); if(paraIds.containsKey(key))return paraIds.get(key);
        Element p=(Element)paraBase.cloneNode(true); String id=String.valueOf(100+paraIds.size()); p.setAttribute("id",id);
        String jc=val(props.get("jc")); String align="center".equals(jc)?"CENTER":("right".equals(jc)||"end".equals(jc))?"RIGHT":("both".equals(jc)||"distribute".equals(jc))?"JUSTIFY":"LEFT";
        child(p,HH,"align").setAttribute("horizontal",align);
        Element ind=props.get("ind"), spacing=props.get("spacing");
        for(Element m:descendants(p,HH,"margin")) {
            // The default branch is for legacy paragraph half-units. The
            // HwpUnitChar case uses physical units (100 per point).
            long units="default".equals(m.getParentNode().getLocalName())?10:5;
            setValue(m,"left",number(firstAttr(ind,"left","start"),0)*units); setValue(m,"right",number(firstAttr(ind,"right","end"),0)*units);
            setValue(m,"intent",(number(attr(ind,"firstLine"),0)-number(attr(ind,"hanging"),0))*units);
            setValue(m,"prev",number(attr(spacing,"before"),0)*units); setValue(m,"next",number(attr(spacing,"after"),0)*units);
        }
        for(Element line:descendants(p,HH,"lineSpacing")) {
            long value=number(attr(spacing,"line"),240); String rule=attr(spacing,"lineRule");
            long units="default".equals(line.getParentNode().getLocalName())?10:5;
            attrs(line,"type",rule.equals("exact")?"FIXED":rule.equals("atLeast")?"AT_LEAST":"PERCENT","value",rule.equals("exact")||rule.equals("atLeast")?value*units:Math.round(value*100.0/240.0));
        }
        Element bs=child(p,HH,"breakSetting"); attrs(bs,"keepWithNext",on(props.get("keepNext"))?1:0,"keepLines",on(props.get("keepLines"))?1:0,"pageBreakBefore",on(props.get("pageBreakBefore"))?1:0);
        Element paras=first(header,HH,"paraProperties"); paras.appendChild(p); paras.setAttribute("itemCnt",String.valueOf(children(paras,HH,"paraPr").size())); paraIds.put(key,id); return id;
    }

    private Element table(Element sourceTable,long available,int depth) throws Exception {
        if(depth>32)throw new EngineException("표 중첩 깊이가 허용 범위를 초과합니다.");
        Element pr=child(sourceTable,W,"tblPr"), grid=child(sourceTable,W,"tblGrid");
        List<Element> rows=children(sourceTable,W,"tr"), cols=children(grid,W,"gridCol");
        if(rows.size()>10000) throw new EngineException("표 행 수가 허용 범위를 초과합니다.");
        int count=cols.size(); if(count==0) for(Element row:rows) {int c=0;for(Element cell:children(row,W,"tc"))c+=Math.max(1,(int)number(val(child(child(cell,W,"tcPr"),W,"gridSpan")),1));count=Math.max(count,c);}
        if(count==0||count>256)throw new EngineException("지원하는 범위의 Word 표가 아닙니다.");
        long[] widths=new long[count]; long sum=0; for(int i=0;i<count;i++){widths[i]=i<cols.size()?Math.max(1,number(attr(cols.get(i),"w"),1440)*5):available/count;sum+=widths[i];}
        Element tw=child(pr,W,"tblW"); long wanted="dxa".equals(attr(tw,"type"))?number(attr(tw,"w"),0)*5:"pct".equals(attr(tw,"type"))?available*number(attr(tw,"w"),5000)/5000:sum;
        if(wanted<=0)wanted=sum; wanted=Math.min(available,wanted); long allocated=0, accumulated=0;
        for(int i=0;i<count;i++){accumulated+=widths[i];long edge=Math.round(accumulated*(double)wanted/sum);widths[i]=Math.max(1,edge-allocated);allocated+=widths[i];}
        wanted=allocated;
        long[] heights=new long[rows.size()];long tableHeight=0;
        for(int i=0;i<rows.size();i++){heights[i]=Math.max(1600,number(val(child(child(rows.get(i),W,"trPr"),W,"trHeight")),0)*5);tableHeight+=heights[i];}
        Element table=el("tbl","id",nextId++,"zOrder",0,"numberingType","TABLE","textWrap","TOP_AND_BOTTOM","textFlow","BOTH_SIDES","lock",0,"dropcapstyle","None","pageBreak","CELL","repeatHeader",on(child(child(rows.isEmpty()?null:rows.get(0),W,"trPr"),W,"tblHeader"))?1:0,"rowCnt",rows.size(),"colCnt",count,"cellSpacing",0,"borderFillIDRef",1,"noAdjust",0);
        table.appendChild(el("sz","width",wanted,"widthRelTo","ABSOLUTE","height",Math.max(1,tableHeight),"heightRelTo","ABSOLUTE","protect",0));
        position(table); table.appendChild(el("outMargin","left",0,"right",0,"top",0,"bottom",0)); table.appendChild(el("inMargin","left",300,"right",300,"top",120,"bottom",120));
        Map<Integer,Element> active=new HashMap<Integer,Element>();
        for(int ri=0;ri<rows.size();ri++) {
            Element row=rows.get(ri),tr=el("tr");table.appendChild(tr); int ci=(int)number(val(child(child(row,W,"trPr"),W,"gridBefore")),0);
            for(Element sc:children(row,W,"tc")) {
                Element cp=child(sc,W,"tcPr");int span=Math.max(1,(int)number(val(child(cp,W,"gridSpan")),1)); if(ci+span>count)throw new EngineException("Word 표의 열 병합 정보가 올바르지 않습니다.");
                Element merge=child(cp,W,"vMerge");
                if(merge!=null&&!"restart".equals(val(merge))) {
                    Element anchor=active.get(ci); if(anchor==null)throw new EngineException("Word 표의 세로 병합 시작 셀을 찾을 수 없습니다.");
                    Element cs=child(anchor,HP,"cellSpan"); if(number(cs.getAttribute("colSpan"),1)!=span)throw new EngineException("Word 표의 세로 병합 너비가 일치하지 않습니다.");
                    cs.setAttribute("rowSpan",String.valueOf(number(cs.getAttribute("rowSpan"),1)+1));
                    Element cellSize=child(anchor,HP,"cellSz");cellSize.setAttribute("height",String.valueOf(number(cellSize.getAttribute("height"),0)+heights[ri]));
                    if(hasText(sc)) {blocks(sc,child(anchor,HP,"subList"),available,depth+1);warnings.add("병합 연속 셀의 글자는 시작 셀에 이어 붙였습니다.");}
                    ci+=span;continue;
                }
                for(int x=ci;x<ci+span;x++)active.remove(x);
                long cw=0;for(int x=ci;x<ci+span;x++)cw+=widths[x];
                Element tc=el("tc","name","","header",on(child(child(row,W,"trPr"),W,"tblHeader"))?1:0,"hasMargin",1,"protect",0,"editable",0,"dirty",0,"borderFillIDRef",border(cp,pr)); tr.appendChild(tc);
                String va=val(child(cp,W,"vAlign")); Element sub=subList(va.equals("center")?"CENTER":va.equals("bottom")?"BOTTOM":"TOP");tc.appendChild(sub);
                Element margins=child(cp,W,"tcMar"); if(margins==null)margins=child(pr,W,"tblCellMar"); long left=cellMargin(margins,"left",300),right=cellMargin(margins,"right",300),top=cellMargin(margins,"top",120),bottom=cellMargin(margins,"bottom",120);
                blocks(sc,sub,Math.max(100,cw-left-right),depth+1); if(children(sub,HP,"p").isEmpty())sub.appendChild(emptyParagraph());
                tc.appendChild(el("cellAddr","colAddr",ci,"rowAddr",ri));tc.appendChild(el("cellSpan","colSpan",span,"rowSpan",1));
                long rh=heights[ri];
                tc.appendChild(el("cellSz","width",cw,"height",Math.max(100,rh)));tc.appendChild(el("cellMargin","left",left,"right",right,"top",top,"bottom",bottom));
                if(merge!=null)active.put(ci,tc); ci+=span;
            }
        }
        return table;
    }

    private String border(Element cellPr,Element tablePr) throws EngineException {
        Element borders=child(cellPr,W,"tcBorders");if(borders==null)borders=child(tablePr,W,"tblBorders"); Element shade=child(cellPr,W,"shd");
        String key=(borders==null?"default":new String(HwpxArchive.serializeXml(asDocument(borders)),StandardCharsets.UTF_8))+"|"+attr(shade,"fill");
        if(borderIds.containsKey(key))return borderIds.get(key);
        Element fills=first(header,HH,"borderFills"),fill=(Element)child(fills,HH,"borderFill").cloneNode(true);String id=String.valueOf(10+borderIds.size());fill.setAttribute("id",id);
        for(String side:new String[]{"left","right","top","bottom"}) {
            Element b=child(borders,W,side); String style=val(b); Element dest=child(fill,HH,side+"Border");
            attrs(dest,"type",b==null?"SOLID":style.equals("nil")||style.equals("none")?"NONE":style.equals("double")?"DOUBLE_SLIM":"SOLID","width",borderWidth(number(attr(b,"sz"),4)),"color",hex(attr(b,"color"),"#808080"));
        }
        String color=attr(shade,"fill");if(color.matches("[0-9a-fA-F]{6}")){Element brush=header.createElementNS(HC,"hc:fillBrush"),win=header.createElementNS(HC,"hc:winBrush");attrs(win,"faceColor","#"+color,"hatchColor","#000000","alpha",0);brush.appendChild(win);fill.appendChild(brush);}
        fills.appendChild(fill);fills.setAttribute("itemCnt",String.valueOf(children(fills,HH,"borderFill").size()));borderIds.put(key,id);return id;
    }

    private void picture(Element drawing,Element run,long width) throws Exception {
        List<Element> blips=descendants(drawing,"http://schemas.openxmlformats.org/drawingml/2006/main","blip");
        if(blips.isEmpty()) {addText(run,"[지원하지 않는 그림·도형]");warnings.add("Word 도형·차트(chart) 또는 VML 그림을 변환하지 못했습니다. 자리표시자를 넣었습니다.");return;}
        for(Element blip:blips) {
            Element rel=relationships.get(blip.getAttributeNS(R,"embed"));
            if(rel==null||"External".equalsIgnoreCase(rel.getAttribute("TargetMode"))) {addText(run,"[외부 그림]");warnings.add("외부 그림은 다운로드하지 않았습니다. 자리표시자로 대체했습니다.");continue;}
            String path=resolve(part,rel.getAttribute("Target"));byte[] bytes=source.get(path);if(bytes==null)throw new EngineException("DOCX에 참조된 그림 파일이 없습니다: "+path);
            String ext=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);String media=ext.equals("jpg")||ext.equals("jpeg")?"image/jpeg":ext.equals("png")?"image/png":ext.equals("gif")?"image/gif":ext.equals("bmp")?"image/bmp":ext.equals("tif")||ext.equals("tiff")?"image/tiff":null;
            if(media==null){addText(run,"[지원하지 않는 그림: "+ext+"]");warnings.add("PNG/JPEG/GIF/BMP/TIFF 이외의 그림은 자리표시자로 대체했습니다.");continue;}
            String id=images.get(path);if(id==null){id="docxImage"+(images.size()+1);images.put(path,id);String dest="BinData/"+id+"."+ext;target.put(dest,bytes);Element item=content.createElementNS(OPF,"opf:item");attrs(item,"id",id,"href",dest,"media-type",media,"isEmbeded",1);first(content,OPF,"manifest").appendChild(item);}
            Element extent=null;
            List<Element> extents=descendants(drawing,"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing","extent");if(!extents.isEmpty())extent=extents.get(0);
            long w=Math.max(100,number(extent==null?"":extent.getAttribute("cx"),914400)/127),h=Math.max(100,number(extent==null?"":extent.getAttribute("cy"),914400)/127);
            if(w>width){h=Math.max(1,h*width/w);w=width;warnings.add("본문보다 넓은 그림은 비율을 유지해 본문 너비로 줄였습니다.");}
            Element pic=el("pic","id",nextId++,"zOrder",0,"numberingType","PICTURE","textWrap","TOP_AND_BOTTOM","textFlow","BOTH_SIDES","lock",0,"dropcapstyle","None","href","","groupLevel",0,"instid",nextId++,"reverse",0);
            pic.appendChild(el("offset","x",0,"y",0));pic.appendChild(el("orgSz","width",w,"height",h));pic.appendChild(el("curSz","width",w,"height",h));pic.appendChild(el("flip","horizontal",0,"vertical",0));pic.appendChild(el("rotationInfo","angle",0,"centerX",w/2,"centerY",h/2,"rotateimage",1));
            Element render=el("renderingInfo");for(String matrix:new String[]{"transMatrix","scaMatrix","rotMatrix"}){Element m=section.createElementNS(HC,"hc:"+matrix);attrs(m,"e1",1,"e2",0,"e3",0,"e4",0,"e5",1,"e6",0);render.appendChild(m);}pic.appendChild(render);
            Element rect=el("imgRect");for(int i=0;i<4;i++){Element pt=section.createElementNS(HC,"hc:pt"+i);attrs(pt,"x",i==1||i==2?w:0,"y",i>=2?h:0);rect.appendChild(pt);}pic.appendChild(rect);
            pic.appendChild(el("imgClip","left",0,"right",w,"top",0,"bottom",h));pic.appendChild(el("inMargin","left",0,"right",0,"top",0,"bottom",0));pic.appendChild(el("imgDim","dimwidth",w,"dimheight",h));Element image=section.createElementNS(HC,"hc:img");attrs(image,"binaryItemIDRef",id,"bright",0,"contrast",0,"effect","REAL_PIC","alpha",0);pic.appendChild(image);
            pic.appendChild(el("sz","width",w,"widthRelTo","ABSOLUTE","height",h,"heightRelTo","ABSOLUTE","protect",0));position(pic);pic.appendChild(el("outMargin","left",0,"right",0,"top",0,"bottom",0));run.appendChild(pic);
            if(!descendants(drawing,"*","anchor").isEmpty()||!descendants(drawing,"*","srcRect").isEmpty())warnings.add("그림의 떠 있는 배치·자르기·회전 효과는 인라인 원본 그림으로 단순화했습니다.");
        }
    }

    private void runningContent(Element sourceSection,Element run) throws Exception {
        if(sourceSection==null)return;
        for(String kind:new String[]{"header","footer"})for(Element ref:children(sourceSection,W,kind+"Reference")) {
            Element relationship=relationships.get(ref.getAttributeNS(R,"id")); if(relationship==null)throw new EngineException("머리말·꼬리말 참조를 찾을 수 없습니다.");
            if("External".equalsIgnoreCase(relationship.getAttribute("TargetMode")))throw new EngineException("외부 머리말·꼬리말은 변환할 수 없습니다.");
            String location=resolve(part,relationship.getAttribute("Target"));Document doc=xml(source,location);
            String savedPart=part;Map<String,Element> saved=relationships;part=location;relationships=rels(part);
            Element ctrl=el("ctrl"),area=el(kind,"id",nextId++,"applyPageType",attr(ref,"type").equals("even")?"EVEN":"BOTH"); Element sub=subList(kind.equals("footer")?"BOTTOM":"TOP");
            Element pageMargin=child(child(secPr,HP,"pagePr"),HP,"margin");attrs(sub,"textWidth",bodyWidth,"textHeight",number(pageMargin.getAttribute(kind),0));
            area.appendChild(sub);ctrl.appendChild(area);
            blocks(doc.getDocumentElement(),sub,bodyWidth,0);if(children(sub,HP,"p").isEmpty())sub.appendChild(emptyParagraph());run.appendChild(ctrl);
            unsupported(doc.getDocumentElement());part=savedPart;relationships=saved;
            warnings.add("머리말(header)·꼬리말(footer)은 글자·기본 서식으로 변환했습니다. Word 필드와 첫 쪽 구분은 별도 확인하세요.");
        }
    }

    private void unsupported(Element root) {
        if(!descendants(root,W,"ins").isEmpty()||!descendants(root,W,"del").isEmpty())warnings.add("변경 추적은 최종 표시 기준으로 변환했습니다. 삭제된 내용은 제외했습니다.");
        for(String name:new String[]{"commentReference","object","txbxContent","bookmarkStart"})if(!descendants(root,W,name).isEmpty())warnings.add("Word "+name+"의 대화형·검토 기능은 보존하지 않습니다.");
    }

    private String listLabel(Element numPr) throws EngineException {
        if(numPr==null||val(child(numPr,W,"numId")).isEmpty()||"0".equals(val(child(numPr,W,"numId"))))return "";
        String num=val(child(numPr,W,"numId")),level=val(child(numPr,W,"ilvl"));if(level.isEmpty())level="0";
        if(numbering==null){warnings.add("번호 목록 정의가 없어 목록 기호를 보존하지 못했습니다.");return "";}
        Element definition=null;for(Element n:descendants(numbering.getDocumentElement(),W,"num"))if(num.equals(attr(n,"numId")))definition=n;
        String abs=val(child(definition,W,"abstractNumId"));Element lvl=null;
        for(Element a:descendants(numbering.getDocumentElement(),W,"abstractNum"))if(abs.equals(attr(a,"abstractNumId")))for(Element l:children(a,W,"lvl"))if(level.equals(attr(l,"ilvl")))lvl=l;
        warnings.add("번호·글머리표 목록은 표시 기호를 일반 텍스트로 변환했습니다. 자동 번호는 갱신되지 않습니다.");
        if(lvl==null)return "• "; String format=val(child(lvl,W,"numFmt")),pattern=val(child(lvl,W,"lvlText"));
        String key=num+":"+level;int count=listCounters.containsKey(key)?listCounters.get(key)+1:(int)number(val(child(lvl,W,"start")),1);listCounters.put(key,count);
        if("bullet".equals(format))return (pattern.isEmpty()?"•":pattern)+" ";
        for(int i=1;i<=9;i++) {Integer value=listCounters.get(num+":"+(i-1));pattern=pattern.replace("%"+i,String.valueOf(i==number(level,0)+1?count:value==null?1:value));}
        if(!"decimal".equals(format))warnings.add("일부 번호 목록 형식은 아라비아 숫자로 단순화했습니다.");return (pattern.isEmpty()?count+".":pattern)+" ";
    }

    private LinkedHashMap<String,Element> properties(Element defaults,String style,String kind,Element direct) throws EngineException {
        LinkedHashMap<String,Element> result=new LinkedHashMap<String,Element>();merge(result,defaults);mergeStyle(result,style,kind,new HashSet<String>());merge(result,direct);return result;
    }
    private void mergeStyle(Map<String,Element> result,String id,String kind,Set<String> seen) throws EngineException {
        if(id==null||id.isEmpty()||!styles.containsKey(id))return;if(!seen.add(id)||seen.size()>64)throw new EngineException("Word 스타일 상속이 순환하거나 너무 깊습니다.");
        Element style=styles.get(id);mergeStyle(result,val(child(style,W,"basedOn")),kind,seen);merge(result,child(style,W,kind));
    }
    private void merge(Map<String,Element> result,Element pr) {
        for(Element p:elements(pr)) {
            String name=p.getLocalName();Element old=result.get(name);
            if(old!=null&&p.hasAttributes()&&old.hasAttributes()) {Element merged=(Element)old.cloneNode(true);NamedNodeMap a=p.getAttributes();for(int i=0;i<a.getLength();i++){Attr attr=(Attr)a.item(i);merged.setAttributeNS(attr.getNamespaceURI(),attr.getName(),attr.getValue());}if(p.hasChildNodes()) {while(merged.hasChildNodes())merged.removeChild(merged.getFirstChild());for(Node n=p.getFirstChild();n!=null;n=n.getNextSibling())merged.appendChild(merged.getOwnerDocument().importNode(n,true));}result.put(name,merged);}
            else result.put(name,p);
        }
    }
    private String propertyKey(Map<String,Element> props) throws EngineException {StringBuilder s=new StringBuilder();for(Map.Entry<String,Element> p:props.entrySet())s.append(p.getKey()).append(new String(HwpxArchive.serializeXml(asDocument(p.getValue())),StandardCharsets.UTF_8));return s.toString();}
    private static Document asDocument(Element element) throws EngineException {Document d=HwpxArchive.parseXml("<root/>".getBytes(StandardCharsets.UTF_8),"properties");d.removeChild(d.getDocumentElement());d.appendChild(d.importNode(element,true));return d;}
    private Map<String,Element> rels(String filename) throws EngineException {String path=filename.substring(0,filename.lastIndexOf('/')+1)+"_rels/"+filename.substring(filename.lastIndexOf('/')+1)+".rels";Map<String,Element> result=new HashMap<String,Element>();if(source.containsKey(path))for(Element e:elements(xml(source,path).getDocumentElement())) {String id=e.getAttribute("Id");if(result.put(id,e)!=null)throw new EngineException("중복된 DOCX 관계 ID입니다.");}return result;}
    private static String resolve(String part,String target) throws EngineException {try {URI uri=new URI(target);if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception();String path=uri.getPath();if(path.startsWith("/"))path=path.substring(1);else path=part.substring(0,part.lastIndexOf('/')+1)+path;Path normalized=Paths.get(path).normalize();String safe=normalized.toString().replace(File.separatorChar,'/');HwpxArchive.safeEntryName(safe);return safe;}catch(Exception e){throw new EngineException("안전하지 않은 DOCX 관계 경로입니다.",e);}}
    private static LinkedHashMap<String,byte[]> readZip(InputStream stream) throws Exception {LinkedHashMap<String,byte[]> result=new LinkedHashMap<String,byte[]>();long total=0;try(ZipInputStream zip=new ZipInputStream(stream,StandardCharsets.UTF_8)){ZipEntry e;while((e=zip.getNextEntry())!=null){if(result.size()>=HwpxArchive.MAX_ENTRIES)throw new EngineException("DOCX 내부 항목 수가 너무 많습니다.");String name=HwpxArchive.safeEntryName(e.getName());if(result.containsKey(name))throw new EngineException("중복된 DOCX 내부 경로입니다: "+name);byte[] bytes=HwpxArchive.readBounded(zip,HwpxArchive.MAX_ENTRY_BYTES,name);total+=bytes.length;if(total>HwpxArchive.MAX_ARCHIVE_BYTES)throw new EngineException("압축을 푼 DOCX 내용이 512MB를 초과합니다.");result.put(name,bytes);zip.closeEntry();}}catch(ZipException e){throw new EngineException("올바른 DOCX ZIP 문서가 아닙니다. 암호화된 문서는 암호를 해제하세요.",e);}return result;}
    private static Document xml(Map<String,byte[]> zip,String name) throws EngineException {byte[] bytes=zip.get(name);if(bytes==null)throw new EngineException("DOCX/HWPX 필수 내부 항목이 없습니다: "+name);return HwpxArchive.parseXml(bytes,name);}
    private Element emptyParagraph(){Element p=el("p","id",nextId++,"paraPrIDRef",0,"styleIDRef",0,"pageBreak",0,"columnBreak",0,"merged",0);p.appendChild(newRun("0"));return p;}
    private Element newRun(String id){return el("run","charPrIDRef",id);}
    private void addText(Element run,String text){Element t=run.getLastChild() instanceof Element && is((Element)run.getLastChild(),HP,"t")?(Element)run.getLastChild():null;if(t==null){t=el("t");run.appendChild(t);}String[] lines=text.split("\n",-1);for(int i=0;i<lines.length;i++){if(i>0)t.appendChild(el("lineBreak"));t.appendChild(section.createTextNode(lines[i]));}}
    private Element subList(String align){return el("subList","id","","textDirection","HORIZONTAL","lineWrap","BREAK","vertAlign",align,"linkListIDRef",0,"linkListNextIDRef",0,"textWidth",0,"textHeight",0,"hasTextRef",0,"hasNumRef",0);}
    private void position(Element parent){parent.appendChild(el("pos","treatAsChar",1,"affectLSpacing",0,"flowWithText",1,"allowOverlap",0,"holdAnchorAndSO",0,"vertRelTo","PARA","horzRelTo","COLUMN","vertAlign","TOP","horzAlign","LEFT","vertOffset",0,"horzOffset",0));}
    private Element el(String name,Object... attributes){Element e=section.createElementNS(HP,"hp:"+name);attrs(e,attributes);return e;}
    private static void attrs(Element e,Object... a){if(e!=null)for(int i=0;i<a.length;i+=2)e.setAttribute(a[i].toString(),a[i+1].toString());}
    private static boolean is(Element e,String ns,String name){return e!=null&&name.equals(e.getLocalName())&&(ns.equals("*")||ns.equals(e.getNamespaceURI()));}
    private static List<Element> elements(Element e){ArrayList<Element> list=new ArrayList<Element>();if(e!=null)for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element)list.add((Element)n);return list;}
    private static List<Element> children(Element e,String ns,String name){ArrayList<Element> list=new ArrayList<Element>();for(Element c:elements(e))if(is(c,ns,name))list.add(c);return list;}
    private static Element child(Element e,String ns,String name){for(Element c:elements(e))if(is(c,ns,name))return c;return null;}
    private static Element first(Document d,String ns,String name){NodeList n=d.getElementsByTagNameNS(ns,name);return n.getLength()==0?null:(Element)n.item(0);}
    private static List<Element> descendants(Element e,String ns,String name){ArrayList<Element> list=new ArrayList<Element>();if(e!=null){NodeList nodes=e.getElementsByTagNameNS(ns,name);for(int i=0;i<nodes.getLength();i++)list.add((Element)nodes.item(i));}return list;}
    private static String attr(Element e,String name){return e==null?"":e.getAttributeNS(W,name);}
    private static String val(Element e){return attr(e,"val");}
    private static String firstAttr(Element e,String a,String b){String s=attr(e,a);return s.isEmpty()?attr(e,b):s;}
    private static long number(String value,long fallback){try{return Long.parseLong(value);}catch(Exception ignored){return fallback;}}
    private static boolean on(Element e){if(e==null)return false;String v=val(e);return !v.equals("0")&&!v.equals("false")&&!v.equals("off")&&!v.equals("none");}
    private static boolean hasText(Element e){return !descendants(e,W,"t").isEmpty()&&!e.getTextContent().trim().isEmpty();}
    private static void removeChildren(Element e,String ns,String name){for(Element c:children(e,ns,name))e.removeChild(c);}
    private static void setValue(Element margin,String name,long value){Element e=child(margin,HC,name);if(e!=null)e.setAttribute("value",String.valueOf(value));}
    private static long cellMargin(Element e,String side,long fallback){Element m=child(e,W,side);return m==null?fallback:Math.max(0,number(attr(m,"w"),fallback/5)*5);}
    private static String hex(String value,String fallback){return value.matches("[a-fA-F0-9]{6}")?"#"+value:fallback;}
    private static String borderWidth(long eighthPoints){double mm=Math.max(0.1,Math.min(5.0,eighthPoints*25.4/576));double[] supported={0.1,0.12,0.15,0.2,0.25,0.3,0.4,0.5,0.6,0.7,1,1.5,2,3,4,5};double best=0.1;for(double n:supported)if(Math.abs(n-mm)<Math.abs(best-mm))best=n;return String.valueOf(best)+" mm";}
}
