package local.nahw.hwpxstudio;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.*;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.header.HeaderFooterApplyPage;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.ShapeComponent;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponenteach.ShapeComponentPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment;
import kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.LineChange;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PaperDirection;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.MakingBookMethod;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.*;
import kr.dogfoot.hwplib.object.docinfo.*;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataType;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataCompress;
import kr.dogfoot.hwplib.object.docinfo.borderfill.*;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PatternType;
import kr.dogfoot.hwplib.object.docinfo.charshape.UnderLineSort;
import kr.dogfoot.hwplib.object.docinfo.charshape.BorderType2;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineSpaceSort;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForEnglish;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForHangul;
import kr.dogfoot.hwplib.object.etc.Color4Byte;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.w3c.dom.*;

import java.nio.file.*;
import java.util.*;

/** Writes binary HWP 5 documents through hwplib after validating the complete HWPX archive. */
final class HwpxToHwpConverter {
    private static final String[] LANGS = {"hangul", "latin", "hanja", "japanese", "other", "symbol", "user"};
    private final HwpxArchive archive;
    private final HWPFile file = BlankFileMaker.make();
    private final Paragraph template = file.getBodyText().getSectionList().get(0).getParagraph(0).clone();
    private final CharShape charTemplate = file.getDocInfo().getCharShapeList().get(0).clone();
    private final ParaShape paraTemplate = file.getDocInfo().getParaShapeList().get(0).clone();
    private final BorderFill borderTemplate = file.getDocInfo().getBorderFillList().get(0).clone();
    private final Map<String, Integer> chars = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> paras = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> borders = new LinkedHashMap<String, Integer>();
    private final Map<String, Element> paraDefinitions = new LinkedHashMap<String, Element>();
    private final Map<String, Map<String, Integer>> fonts = new LinkedHashMap<String, Map<String, Integer>>();
    private final Map<String, Integer> images = new LinkedHashMap<String, Integer>();
    private final LinkedHashSet<String> warnings = new LinkedHashSet<String>();
    private int paragraphCount;
    private int pictureCount;
    private int tableCount;

    private HwpxToHwpConverter(HwpxArchive archive) { this.archive = archive; }

    static Map<String, Object> convert(Path input, Path output) throws Exception {
        Path destination = output.toAbsolutePath().normalize();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hwp")) {
            throw new EngineException("HWP 출력 파일의 확장자는 .hwp여야 합니다.");
        }
        HwpxArchive.ensureDistinctInputOutput(input, destination);
        HwpxToHwpConverter converter = new HwpxToHwpConverter(HwpxArchive.read(input, true));
        converter.convertDocument();
        Path temp = Files.createTempFile(destination.getParent(), ".studio-hwp-", ".hwp");
        try {
            List<String> expected = textInventory(converter.file);
            Map<String, byte[]> expectedBinaries = binaryInventory(converter.file);
            HWPWriter.toFile(converter.file, temp.toString());
            HWPFile checked = HWPReader.fromFile(temp.toString());
            if (checked == null || !expected.equals(textInventory(checked)) ||
                    checked.getBodyText().getSectionList().size() != converter.file.getBodyText().getSectionList().size()) {
                throw new EngineException("생성된 HWP를 다시 열어 본문·표·머리말·꼬리말을 검증하지 못했습니다. 출력하지 않았습니다.");
            }
            if (!equalBinaries(expectedBinaries, binaryInventory(checked))) {
                throw new EngineException("생성된 HWP의 그림 데이터 검증에 실패했습니다. 출력하지 않았습니다.");
            }
            HwpxArchive.publishTempNoClobber(temp, destination);
        } finally { Files.deleteIfExists(temp); }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("output", destination.toString());
        result.put("warnings", new ArrayList<String>(converter.warnings));
        result.put("paragraphCount", converter.paragraphCount);
        result.put("tableCount", converter.tableCount);
        result.put("pictureCount", converter.pictureCount);
        return result;
    }

    private void convertDocument() throws Exception {
        // Master pages, scripts and annotations carry content outside ordinary sections.
        for (String name : archive.names()) {
            if (name.matches("Contents/masterpage[0-9]+\\.xml") || name.startsWith("Scripts/") || name.matches("Contents/(?:memo|comment).*\\.xml")) {
                byte[] data = archive.bytes(name);
                if (data != null && data.length > 0) throw unsupported("문서의 바탕쪽·스크립트·메모 (" + name + ")");
            }
        }
        Document header = archive.parse("Contents/header.xml");
        Element refs = child(header.getDocumentElement(), "refList");
        if (refs == null) throw new EngineException("HWPX 서식 참조 목록이 없습니다.");
        loadFonts(child(refs, "fontfaces"));
        loadBorders(child(refs, "borderFills"));
        loadCharacters(child(refs, "charProperties"));
        loadParagraphs(child(refs, "paraProperties"));
        loadImages();
        warnings.add("사용자 정의 스타일 이름과 탭 위치·일부 세부 배치 효과는 적용된 글자·문단 서식과 기본 탭으로 근사했습니다.");
        file.getBodyText().getSectionList().clear();
        for (String sectionName : archive.sectionNames()) {
            Element root = archive.parse(sectionName).getDocumentElement();
            validateSectionNamespaces(root);
            if (!"sec".equals(local(root))) throw unsupported("본문 XML 루트 " + local(root));
            Section section = file.getBodyText().addNewSection();
            int count = 0;
            for (Element p : children(root)) {
                if (!"p".equals(local(p))) throw unsupported("본문 요소 " + local(p));
                Paragraph converted = paragraph(p, count == 0);
                section.insertParagraph(count++, converted);
            }
            if (count == 0) throw new EngineException("빈 본문 section을 안전하게 변환할 수 없습니다.");
        }
        warnings.add("HWP 5로 저장했습니다. 글꼴 대체와 줄바꿈·쪽 배치는 한컴에서 최종 확인하세요.");
    }

    private List<FaceName> fontList(int index) {
        DocInfo d = file.getDocInfo();
        switch (index) {
            case 0: return d.getHangulFaceNameList(); case 1: return d.getEnglishFaceNameList();
            case 2: return d.getHanjaFaceNameList(); case 3: return d.getJapaneseFaceNameList();
            case 4: return d.getEtcFaceNameList(); case 5: return d.getSymbolFaceNameList();
            default: return d.getUserFaceNameList();
        }
    }

    private void loadFonts(Element container) throws Exception {
        for (int i = 0; i < LANGS.length; i++) fonts.put(LANGS[i], new LinkedHashMap<String, Integer>());
        if (container == null) return;
        for (Element face : children(container)) {
            if (!"fontface".equals(local(face))) throw unsupported("글꼴 목록 " + local(face));
            String lang = attr(face, "lang", "").toLowerCase(Locale.ROOT);
            int language = Arrays.asList(LANGS).indexOf(lang);
            if (language < 0) throw unsupported("글꼴 언어 " + lang);
            List<FaceName> list = fontList(language);
            FaceName defaultFont = list.get(0).clone();
            list.clear();
            for (Element font : children(face)) {
                if (!"font".equals(local(font))) throw unsupported("글꼴 " + local(font));
                FaceName name = defaultFont.clone();
                name.setName(attr(font, "face", defaultFont.getName()));
                String id = required(font, "id");
                putUnique(fonts.get(lang), id, list.size(), "글꼴");
                list.add(name);
            }
            if (list.isEmpty()) list.add(defaultFont);
        }
    }

    private void loadBorders(Element container) throws Exception {
        file.getDocInfo().getBorderFillList().clear();
        if (container != null) for (Element e : children(container)) {
            if (!"borderFill".equals(local(e))) throw unsupported("테두리 정의 " + local(e));
            BorderFill fill = borderTemplate.clone();
            fill.getFillInfo().getType().setValue(0);
            border(child(e, "leftBorder"), fill.getLeftBorder());
            border(child(e, "rightBorder"), fill.getRightBorder());
            border(child(e, "topBorder"), fill.getTopBorder());
            border(child(e, "bottomBorder"), fill.getBottomBorder());
            border(child(e, "diagonal"), fill.getDiagonalBorder());
            Element brush = child(e, "fillBrush");
            if (brush != null) for (Element b : children(brush)) {
                if (!"winBrush".equals(local(b))) throw unsupported("표·문단의 그라데이션/이미지 배경 " + local(b));
                fill.getFillInfo().getType().setPatternFill(true);
                fill.getFillInfo().createPatternFill();
                color(attr(b, "faceColor", "#FFFFFF"), fill.getFillInfo().getPatternFill().getBackColor());
                color(attr(b, "hatchColor", "#000000"), fill.getFillInfo().getPatternFill().getPatternColor());
                String hatch = attr(b, "hatchStyle", "NONE");
                if (!"NONE".equals(hatch)) warnings.add("배경 빗금은 단색 채우기로 근사했습니다.");
                fill.getFillInfo().getPatternFill().setPatternType(PatternType.None);
                if (number(b, "alpha", 0) != 0) warnings.add("배경 투명도는 HWP 기본 채우기로 근사했습니다.");
            }
            String id = required(e, "id");
            putUnique(borders, id, file.getDocInfo().getBorderFillList().size() + 1, "테두리");
            file.getDocInfo().getBorderFillList().add(fill);
        }
        if (file.getDocInfo().getBorderFillList().isEmpty()) file.getDocInfo().getBorderFillList().add(borderTemplate.clone());
    }

    private void border(Element e, EachBorder target) throws Exception {
        if (e == null) { target.setType(BorderType.None); return; }
        String style = attr(e, "type", "NONE");
        Map<String, BorderType> map = new HashMap<String, BorderType>();
        map.put("NONE", BorderType.None); map.put("SOLID", BorderType.Solid); map.put("DASH", BorderType.Dash);
        map.put("DOT", BorderType.Dot); map.put("DASH_DOT", BorderType.DashDot); map.put("DASH_DOT_DOT", BorderType.DashDotDot);
        map.put("LONG_DASH", BorderType.LongDash); map.put("CIRCLE", BorderType.CircleDot); map.put("DOUBLE_SLIM", BorderType.Double);
        map.put("SLIM_THICK", BorderType.ThinThick); map.put("THICK_SLIM", BorderType.ThickThin); map.put("SLIM_THICK_SLIM", BorderType.ThinThickThin);
        if (!map.containsKey(style)) { warnings.add("일부 테두리 선 모양은 실선으로 근사했습니다."); target.setType(BorderType.Solid); }
        else target.setType(map.get(style));
        String width = attr(e, "width", "0.12 mm").replace("mm", "").trim();
        double[] values = {.1,.12,.15,.2,.25,.3,.4,.5,.6,.7,1,1.5,2,3,4,5};
        double value;
        try { value = Double.parseDouble(width); } catch (NumberFormatException ex) { throw new EngineException("잘못된 테두리 두께: " + width); }
        int best = 0;
        for (int i=1;i<values.length;i++) if (Math.abs(values[i]-value)<Math.abs(values[best]-value)) best=i;
        target.setThickness(BorderThickness.values()[best]);
        color(attr(e, "color", "#000000"), target.getColor());
    }

    private void loadCharacters(Element container) throws Exception {
        file.getDocInfo().getCharShapeList().clear();
        if (container != null) for (Element e : children(container)) {
            if (!"charPr".equals(local(e))) throw unsupported("글자 서식 " + local(e));
            CharShape shape = charTemplate.clone();
            shape.getProperty().setValue(0);
            shape.setBaseSize((int) number(e, "height", 1000));
            color(attr(e,"textColor","#000000"),shape.getCharColor());
            color(attr(e,"shadeColor","none"),shape.getShadeColor());
            shape.setBorderFillId(borderRef(attr(e,"borderFillIDRef","0")));
            Element font = child(e,"fontRef");
            if (font != null) {
                int[] ids = new int[7];
                for (int i=0;i<7;i++) {
                    String id=attr(font,LANGS[i],"0");
                    Map<String,Integer> map=fonts.get(LANGS[i]);
                    if (map.isEmpty() && "0".equals(id)) ids[i]=0;
                    else ids[i]=ref(map,id,"글꼴");
                }
                shape.getFaceNameIds().setArray(ids);
            }
            Element ratio=child(e,"ratio"), spacing=child(e,"spacing"), relative=child(e,"relSz"), offset=child(e,"offset");
            short[] ratios=new short[7], sizes=new short[7]; byte[] spaces=new byte[7], offsets=new byte[7];
            for(int i=0;i<7;i++){ratios[i]=(short)number(ratio,LANGS[i],100);sizes[i]=(short)number(relative,LANGS[i],100);spaces[i]=(byte)number(spacing,LANGS[i],0);offsets[i]=(byte)number(offset,LANGS[i],0);}
            shape.getRatios().setArray(ratios);shape.getRelativeSizes().setArray(sizes);shape.getCharSpaces().setArray(spaces);shape.getCharOffsets().setArray(offsets);
            shape.getProperty().setBold(child(e,"bold")!=null);shape.getProperty().setItalic(child(e,"italic")!=null);
            shape.getProperty().setSuperScript(child(e,"supscript")!=null);shape.getProperty().setSubScript(child(e,"subscript")!=null);
            shape.getProperty().setEmboss(child(e,"emboss")!=null);shape.getProperty().setEngrave(child(e,"engrave")!=null);
            shape.getProperty().setKerning(bool(e,"useKerning"));shape.getProperty().setUsingSpaceAppropriateForFont(bool(e,"useFontSpace"));
            Element underline=child(e,"underline");
            if(underline!=null){String type=attr(underline,"type","NONE");shape.getProperty().setUnderLineSort("TOP".equals(type)?UnderLineSort.Top:"BOTTOM".equals(type)?UnderLineSort.Bottom:UnderLineSort.None);shape.getProperty().setUnderLineShape(BorderType2.Solid);color(attr(underline,"color","#000000"),shape.getUnderLineColor());if(!"SOLID".equals(attr(underline,"shape","SOLID"))&&!"NONE".equals(type))warnings.add("일부 밑줄 모양은 실선으로 근사했습니다.");}
            Element strike=child(e,"strikeout");if(strike!=null&&!"NONE".equals(attr(strike,"shape","NONE"))){shape.getProperty().setStrikeLine(true);shape.getProperty().setStrikeLineShape(BorderType2.Solid);color(attr(strike,"color","#000000"),shape.getStrikeLineColor());}
            for(Element c:children(e))if(!Arrays.asList("fontRef","ratio","spacing","relSz","offset","bold","italic","supscript","subscript","emboss","engrave","underline","strikeout","outline","shadow").contains(local(c)))throw unsupported("글자 서식 "+local(c));
            if(child(e,"outline")!=null&&!"NONE".equals(attr(child(e,"outline"),"type","NONE")))warnings.add("글자 외곽선 효과는 기본 글자 모양으로 근사했습니다.");
            if(child(e,"shadow")!=null&&!"NONE".equals(attr(child(e,"shadow"),"type","NONE")))warnings.add("글자 그림자 효과는 기본 글자 모양으로 근사했습니다.");
            putUnique(chars,required(e,"id"),file.getDocInfo().getCharShapeList().size(),"글자 서식");
            file.getDocInfo().getCharShapeList().add(shape);
        }
        if(chars.isEmpty()){chars.put("0",0);file.getDocInfo().getCharShapeList().add(charTemplate.clone());}
    }

    private void loadParagraphs(Element container) throws Exception {
        file.getDocInfo().getParaShapeList().clear();
        if(container!=null)for(Element e:children(container)){
            if(!"paraPr".equals(local(e)))throw unsupported("문단 서식 "+local(e));
            ParaShape shape=paraTemplate.clone();
            shape.setTabDefId(0);shape.setParaHeadId(0);shape.setBorderFillId(0);
            Element align=child(e,"align");String h=attr(align,"horizontal","JUSTIFY");
            shape.getProperty1().setAlignment("LEFT".equals(h)?Alignment.Left:"RIGHT".equals(h)?Alignment.Right:"CENTER".equals(h)?Alignment.Center:"DISTRIBUTE".equals(h)?Alignment.Distribute:Alignment.Justify);
            Element heading=child(e,"heading");if(heading!=null&&!"NONE".equals(attr(heading,"type","NONE")))paraDefinitions.put(required(e,"id"),heading);
            ParagraphSetting margin=paragraphSetting(e,"margin"), line=paragraphSetting(e,"lineSpacing");
            if(margin.element!=null){shape.setLeftMargin(marginValue(margin,"left"));shape.setRightMargin(marginValue(margin,"right"));shape.setIndent(marginValue(margin,"intent"));shape.setTopParaSpace(marginValue(margin,"prev"));shape.setBottomParaSpace(marginValue(margin,"next"));}
            if(line.element!=null){String t=attr(line.element,"type","PERCENT");LineSpaceSort sort="FIXED".equals(t)?LineSpaceSort.FixedValue:"AT_LEAST".equals(t)?LineSpaceSort.AtLeast:"BETWEEN_LINES".equals(t)?LineSpaceSort.OnlyMargin:LineSpaceSort.RatioForLetter;int value=scaledParagraphValue(line.element,"value",160,"PERCENT".equals(t)?1:line.scale);shape.getProperty1().setLineSpaceSort(sort);shape.getProperty3().setLineSpaceSort(sort);shape.setLineSpace(value);shape.setLineSpace2(value);}
            shape.getProperty1().setUseGrid(bool(e,"snapToGrid"));
            shape.getProperty1().setLineHeightForFont(bool(e,"fontLineHeight"));
            shape.getProperty1().setMinimumSpace((byte)number(e,"condense",0));
            Element br=child(e,"breakSetting");if(br!=null){shape.getProperty1().setProtectLoner(bool(br,"widowOrphan"));shape.getProperty1().setTogetherNextPara(bool(br,"keepWithNext"));shape.getProperty1().setProtectPara(bool(br,"keepLines"));shape.getProperty1().setSplitPageBeforePara(bool(br,"pageBreakBefore"));String latin=attr(br,"breakLatinWord","KEEP_WORD");shape.getProperty1().setLineDivideForEnglish("HYPHENATION".equals(latin)?LineDivideForEnglish.ByHypen:"BREAK_WORD".equals(latin)?LineDivideForEnglish.ByLetter:LineDivideForEnglish.ByWord);shape.getProperty1().setLineDivideForHangul("KEEP_WORD".equals(attr(br,"breakNonLatinWord","BREAK_WORD"))?LineDivideForHangul.ByLetter:LineDivideForHangul.ByWord);shape.getProperty2().setInputSingleLine("SQUEEZE".equals(attr(br,"lineWrap","BREAK")));}
            Element border=child(e,"border");if(border!=null){shape.setBorderFillId(borderRef(attr(border,"borderFillIDRef","0")));shape.setLeftBorderSpace((short)number(border,"offsetLeft",0));shape.setRightBorderSpace((short)number(border,"offsetRight",0));shape.setTopBorderSpace((short)number(border,"offsetTop",0));shape.setBottomBorderSpace((short)number(border,"offsetBottom",0));}
            String id=required(e,"id");putUnique(paras,id,file.getDocInfo().getParaShapeList().size(),"문단 서식");file.getDocInfo().getParaShapeList().add(shape);
        }
        if(paras.isEmpty()){paras.put("0",0);file.getDocInfo().getParaShapeList().add(paraTemplate.clone());}
        // Applied paragraph/run references preserve appearance; named style inheritance is flattened.
        for(Style style:file.getDocInfo().getStyleList()){style.setParaShapeId(0);style.setCharShapeId(0);}
    }

    private void loadImages() throws Exception {
        if(archive.bytes("Contents/content.hpf")==null)return;
        Document manifest=archive.parse("Contents/content.hpf");
        NodeList items=manifest.getElementsByTagNameNS("*","item");
        for(int i=0;i<items.getLength();i++){
            Element item=(Element)items.item(i);String href=attr(item,"href","");
            if(!href.startsWith("BinData/"))continue;
            byte[] bytes=archive.bytes(href);if(bytes==null)throw new EngineException("그림 바이너리를 찾을 수 없습니다: "+href);
            String ext=href.substring(href.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            if(!Arrays.asList("png","jpg","jpeg","bmp","gif","wmf","emf","tif","tiff").contains(ext))throw unsupported("그림 형식 "+ext);
            int id=file.getDocInfo().getBinDataList().size()+1;
            BinData data=file.getDocInfo().addNewBinData();data.getProperty().setType(BinDataType.Embedding);data.getProperty().setCompress(BinDataCompress.NoCompress);data.setBinDataID(id);data.setExtensionForEmbedding(ext);
            file.getBinData().addNewEmbeddedBinaryData(String.format(Locale.ROOT,"BIN%04X.%s",id,ext),bytes,BinDataCompress.NoCompress);
            putUnique(images,required(item,"id"),id,"그림");
        }
    }

    private Paragraph paragraph(Element source,boolean first) throws Exception {
        if(++paragraphCount>200000)throw new EngineException("HWP 변환 문단 수가 허용 범위를 초과합니다.");
        if(bool(source,"merged"))throw unsupported("변경 추적된 문단");
        Paragraph p=template.clone();p.deleteText();p.createText();p.deleteCharShape();p.createCharShape();p.deleteLineSeg();p.deleteRangeTag();
        if(!first)p.getControlList().clear();
        else {extend(p,2,"secd");extend(p,2,"cold");}
        p.getHeader().setStyleId((short)0);String paraRef=attr(source,"paraPrIDRef","0");p.getHeader().setParaShapeId(ref(paras,paraRef,"문단 서식"));
        if(paraDefinitions.containsKey(paraRef))throw unsupported("자동 번호·글머리표 문단 (문단 서식 "+paraRef+")");
        p.getHeader().getDivideSort().setDividePage(bool(source,"pageBreak"));p.getHeader().getDivideSort().setDivideColumn(bool(source,"columnBreak"));
        p.getHeader().setInstanceID(number(source,"id",0));
        boolean ran=false;
        for(Element run:children(source)){
            if("linesegarray".equals(local(run)))continue;
            if(!"run".equals(local(run)))throw unsupported("문단 요소 "+local(run));
            int shape=ref(chars,attr(run,"charPrIDRef","0"),"글자 서식");
            p.getCharShape().addParaCharShape(ran?p.getText().getCharSize():0,shape);ran=true;
            for(Element e:children(run))content(e,p,first);
        }
        if(!ran)p.getCharShape().addParaCharShape(0,0);
        p.getText().addNewCharControlChar().setCode((short)13);
        return p;
    }

    private void content(Element e,Paragraph p,boolean first) throws Exception {
        String tag=local(e);
        if("t".equals(tag)){text(e,p);return;}
        if("secPr".equals(tag)){if(!first)throw unsupported("문단 중간의 구역 정의");sectionProperties(e,p);return;}
        if("ctrl".equals(tag)){for(Element c:children(e))control(c,p,first);return;}
        if("tbl".equals(tag)){table(e,p);return;}
        if("pic".equals(tag)){picture(e,p);return;}
        throw unsupported("본문 객체 "+tag);
    }

    private void text(Element e,Paragraph p) throws Exception {
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()){
            if(n.getNodeType()==Node.TEXT_NODE||n.getNodeType()==Node.CDATA_SECTION_NODE){String s=n.getNodeValue();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\n'||c=='\r')p.getText().addNewCharControlChar().setCode(10);else if(c=='\t')tab(p);else if(c<32)throw unsupported("제어 문자 "+(int)c);else p.getText().addNewNormalChar().setCode(c);}}
            else if(n instanceof Element){String tag=local(n);if("lineBreak".equals(tag))p.getText().addNewCharControlChar().setCode((short)10);else if("tab".equals(tag))tab(p);else if("nbSpace".equals(tag))p.getText().addNewCharControlChar().setCode((short)30);else if("fwSpace".equals(tag))p.getText().addNewCharControlChar().setCode((short)31);else if("hyphen".equals(tag))p.getText().addNewCharControlChar().setCode((short)24);else throw unsupported("텍스트 내부 요소 "+tag);}
        }
    }

    /** hwplib convenience add methods append a paragraph terminator; use raw records
     * until all runs and their UTF-16 positions have been assembled. */
    private static void extend(Paragraph p,int code,String controlID)throws Exception{
        HWPCharControlExtend control=p.getText().addNewExtendControlChar();
        control.setCode(code);
        byte[] addition=new byte[12];
        for(int i=0;i<4;i++)addition[3-i]=(byte)controlID.charAt(i);
        control.setAddition(addition);
    }

    private void tab(Paragraph p) throws Exception {HWPCharControlInline tab=p.getText().addNewInlineControlChar();tab.setCode((short)9);tab.setAddition(new byte[12]);}

    private void control(Element e,Paragraph p,boolean first) throws Exception {
        String tag=local(e);
        if("colPr".equals(tag)){if(!first||number(e,"colCount",1)!=1)throw unsupported("다단 설정");return;}
        if("header".equals(tag)||"footer".equals(tag)){
            Element list=child(e,"subList");if(list==null)throw new EngineException("머리말·꼬리말 본문이 없습니다.");
            ParagraphList target;HeaderFooterApplyPage page=applyPage(attr(e,"applyPageType","BOTH"));
            if("header".equals(tag)){extend(p,16,"head");ControlHeader c=(ControlHeader)p.addNewControl(ControlType.Header);c.getHeader().setApplyPage(page);c.getHeader().setCreateIndex((int)number(e,"id",0));c.getListHeader().setTextWidth(number(list,"textWidth",0));c.getListHeader().setTextHeight(number(list,"textHeight",0));target=c.getParagraphList();}
            else {extend(p,16,"foot");ControlFooter c=(ControlFooter)p.addNewControl(ControlType.Footer);c.getHeader().setApplyPage(page);c.getHeader().setCreateIndex((int)number(e,"id",0));c.getListHeader().setTextWidth(number(list,"textWidth",0));c.getListHeader().setTextHeight(number(list,"textHeight",0));target=c.getParagraphList();}
            subList(list,target);return;
        }
        throw unsupported("필드·각주·수식·제어 요소 "+tag);
    }

    private void subList(Element list,ParagraphList target) throws Exception {
        checkHorizontal(list);
        for(Element e:children(list)){if(!"p".equals(local(e)))throw unsupported("하위 문단 "+local(e));target.addParagraph(paragraph(e,false));}
        if(target.getParagraphCount()==0)throw new EngineException("표·머리말·꼬리말에 빈 하위 문단 목록이 있습니다.");
    }

    private void table(Element source,Paragraph p) throws Exception {
        tableCount++;extend(p,11,"tbl ");ControlTable table=(ControlTable)p.addNewControl(ControlType.Table);shape(source,table.getHeader());
        if(child(source,"caption")!=null)throw unsupported("표 캡션");
        Table data=table.getTable();int rows=(int)number(source,"rowCnt",0),cols=(int)number(source,"colCnt",0);
        if(rows<1||cols<1||rows>10000||cols>1000)throw new EngineException("표 행·열 수가 허용 범위를 벗어납니다.");
        data.setRowCount(rows);data.setColumnCount(cols);data.setCellSpacing((int)number(source,"cellSpacing",0));data.setBorderFillId(borderRef(attr(source,"borderFillIDRef","0")));
        data.getProperty().setAutoRepeatTitleRow(bool(source,"repeatHeader"));String breakAt=attr(source,"pageBreak","CELL");data.getProperty().setDivideAtPageBoundary("NONE".equals(breakAt)?DivideAtPageBoundary.NoDivide:"TABLE".equals(breakAt)?DivideAtPageBoundary.DivideByCell:DivideAtPageBoundary.Divide);
        Element margin=child(source,"inMargin");data.setLeftInnerMargin((int)number(margin,"left",0));data.setRightInnerMargin((int)number(margin,"right",0));data.setTopInnerMargin((int)number(margin,"top",0));data.setBottomInnerMargin((int)number(margin,"bottom",0));
        boolean[][] occupied=new boolean[rows][cols];int row=0;
        for(Element tr:children(source)){
            if(!"tr".equals(local(tr))){if(!Arrays.asList("sz","pos","outMargin","inMargin","cellzoneList").contains(local(tr)))throw unsupported("표 요소 "+local(tr));continue;}
            if(row>=rows)throw new EngineException("표의 행 수가 선언과 다릅니다.");
            Row out=table.addNewRow();
            for(Element tc:children(tr)){
                if(!"tc".equals(local(tc)))throw unsupported("표 행 요소 "+local(tc));
                for(Element child:children(tc))if(!Arrays.asList("subList","cellAddr","cellSpan","cellSz","cellMargin").contains(local(child)))throw unsupported("표 셀 요소 "+local(child));
                Cell cell=out.addNewCell();ListHeaderForCell header=cell.getListHeader();Element addr=child(tc,"cellAddr"),span=child(tc,"cellSpan"),size=child(tc,"cellSz"),cm=child(tc,"cellMargin"),list=child(tc,"subList");
                int r=(int)number(addr,"rowAddr",row),c=(int)number(addr,"colAddr",0),rs=(int)number(span,"rowSpan",1),cs=(int)number(span,"colSpan",1);
                if(r!=row||r<0||c<0||rs<1||cs<1||r+rs>rows||c+cs>cols)throw new EngineException("표 셀 병합 범위가 올바르지 않습니다.");
                for(int rr=r;rr<r+rs;rr++)for(int cc=c;cc<c+cs;cc++){if(occupied[rr][cc])throw new EngineException("표 셀 병합 범위가 겹칩니다.");occupied[rr][cc]=true;}
                header.setRowIndex(r);header.setColIndex(c);header.setRowSpan(rs);header.setColSpan(cs);if(!"ABSOLUTE".equals(attr(size,"widthRelTo","ABSOLUTE"))||!"ABSOLUTE".equals(attr(size,"heightRelTo","ABSOLUTE")))warnings.add("상대 크기 개체는 저장된 폭·높이의 절대 크기로 근사했습니다.");
        header.setWidth(number(size,"width",1000));header.setHeight(number(size,"height",1000));
                header.setBorderFillId(borderRef(attr(tc,"borderFillIDRef","0")));header.setFieldName(attr(tc,"name",""));header.getProperty().setTitleCell(bool(tc,"header"));header.getProperty().setApplyInnerMagin(bool(tc,"hasMargin"));header.getProperty().setProtectCell(bool(tc,"protect"));header.getProperty().setEditableAtFormMode(bool(tc,"editable"));
                header.setLeftMargin((int)number(cm,"left",0));header.setRightMargin((int)number(cm,"right",0));header.setTopMargin((int)number(cm,"top",0));header.setBottomMargin((int)number(cm,"bottom",0));
                if(list==null)throw new EngineException("표 셀 본문이 없습니다.");header.getProperty().setTextVerticalAlignment(vertical(attr(list,"vertAlign","TOP")));String wrap=attr(list,"lineWrap","BREAK");header.getProperty().setLineChange("SQUEEZE".equals(wrap)?LineChange.KeepOneLineByAdjustWordSpace:"KEEP".equals(wrap)?LineChange.IncreaseWidthByContent:LineChange.Normal);subList(list,cell.getParagraphList());
            }
            data.addCellCountOfRow(out.getCellList().size());row++;
        }
        if(row!=rows)throw new EngineException("표의 행 수가 선언과 다릅니다.");
        for(boolean[] line:occupied)for(boolean value:line)if(!value)throw new EngineException("표에 정의되지 않은 셀 영역이 있습니다.");
        Element zones=child(source,"cellzoneList");if(zones!=null)for(Element zone:children(zones)){if(!"cellzone".equals(local(zone)))throw unsupported("표 영역 "+local(zone));ZoneInfo z=data.addNewZoneInfo();z.setStartRow((int)number(zone,"startRowAddr",0));z.setStartColumn((int)number(zone,"startColAddr",0));z.setEndRow((int)number(zone,"endRowAddr",0));z.setEndColumn((int)number(zone,"endColAddr",0));z.setBorderFillId(borderRef(attr(zone,"borderFillIDRef","0")));}
    }

    private void shape(Element source,CtrlHeaderGso header) throws Exception {
        Element size=child(source,"sz"),pos=child(source,"pos"),margin=child(source,"outMargin");
        if(!"ABSOLUTE".equals(attr(size,"widthRelTo","ABSOLUTE"))||!"ABSOLUTE".equals(attr(size,"heightRelTo","ABSOLUTE")))warnings.add("상대 크기 개체는 저장된 폭·높이의 절대 크기로 근사했습니다.");
        header.setWidth(number(size,"width",1000));header.setHeight(number(size,"height",1000));header.setInstanceId(number(source,"id",0));header.setzOrder((int)number(source,"zOrder",0));
        header.setxOffset(number(pos,"horzOffset",0));header.setyOffset(number(pos,"vertOffset",0));
        header.setOutterMarginLeft((int)number(margin,"left",0));header.setOutterMarginRight((int)number(margin,"right",0));header.setOutterMarginTop((int)number(margin,"top",0));header.setOutterMarginBottom((int)number(margin,"bottom",0));
        GsoHeaderProperty prop=header.getProperty();prop.setLikeWord(bool(pos,"treatAsChar"));prop.setApplyLineSpace(bool(pos,"affectLSpacing"));prop.setAllowOverlap(bool(pos,"allowOverlap"));
        String v=attr(pos,"vertRelTo","PARA"),h=attr(pos,"horzRelTo","COLUMN");prop.setVertRelTo("PAPER".equals(v)?VertRelTo.Paper:"PAGE".equals(v)?VertRelTo.Page:VertRelTo.Para);prop.setHorzRelTo("PAPER".equals(h)?HorzRelTo.Paper:"PAGE".equals(h)?HorzRelTo.Page:"PARA".equals(h)?HorzRelTo.Para:HorzRelTo.Column);
        prop.setVertRelativeArrange(arrange(attr(pos,"vertAlign","TOP")));prop.setHorzRelativeArrange(arrange(attr(pos,"horzAlign","LEFT")));
        prop.setVertRelToParaLimit(bool(pos,"flowWithText"));header.setPreventPageDivide(bool(pos,"holdAnchorAndSO"));
        String wrap=attr(source,"textWrap","SQUARE");prop.setTextFlowMethod("TOP_AND_BOTTOM".equals(wrap)?TextFlowMethod.TakePlace:"BEHIND_TEXT".equals(wrap)?TextFlowMethod.BehindText:"IN_FRONT_OF_TEXT".equals(wrap)?TextFlowMethod.InFrontOfText:TextFlowMethod.FitWithText);
        String flow=attr(source,"textFlow","BOTH_SIDES");prop.setTextHorzArrange("LEFT_ONLY".equals(flow)?TextHorzArrange.LeftOnly:"RIGHT_ONLY".equals(flow)?TextHorzArrange.RightOnly:"LARGEST_ONLY".equals(flow)?TextHorzArrange.LargestOnly:TextHorzArrange.BothSides);prop.setWidthCriterion(WidthCriterion.Absolute);prop.setHeightCriterion(HeightCriterion.Absolute);
    }

    private void picture(Element source,Paragraph p) throws Exception {
        for(Element child:children(source))if(!Arrays.asList("sz","pos","outMargin","offset","orgSz","curSz","flip","rotationInfo","renderingInfo","lineShape","imgRect","imgClip","inMargin","imgDim","img","effects","shapeComment").contains(local(child)))throw unsupported("그림 요소 "+local(child));
        Element effects=child(source,"effects");if(effects!=null&&!children(effects).isEmpty())warnings.add("그림 효과는 원본 이미지와 크기를 보존하여 기본 그림으로 근사했습니다.");
        Element comment=child(source,"shapeComment");if(comment!=null&&!comment.getTextContent().isEmpty())warnings.add("그림 설명은 HWP 개체 설명으로 보존했습니다.");
        Element image=child(source,"img");if(image==null)throw new EngineException("그림 이미지 참조가 없습니다.");int imageID=ref(images,required(image,"binaryItemIDRef"),"그림");
        extend(p,11,"gso ");ControlPicture picture=(ControlPicture)p.addNewGsoControl(GsoControlType.Picture);shape(source,picture.getHeader());
        if(comment!=null)picture.getHeader().getExplanation().fromUTF16LEString(comment.getTextContent());
        ShapeComponent component=picture.getShapeComponent();Element original=child(source,"orgSz"),current=child(source,"curSz"),offset=child(source,"offset"),rotation=child(source,"rotationInfo"),flip=child(source,"flip");
        component.setWidthAtCreate((int)number(original,"width",picture.getHeader().getWidth()));component.setHeightAtCreate((int)number(original,"height",picture.getHeader().getHeight()));component.setWidthAtCurrent((int)number(current,"width",picture.getHeader().getWidth()));component.setHeightAtCurrent((int)number(current,"height",picture.getHeader().getHeight()));component.setOffsetX((int)number(offset,"x",0));component.setOffsetY((int)number(offset,"y",0));component.setRotateAngle((int)number(rotation,"angle",0));component.setRotateXCenter((int)number(rotation,"centerX",0));component.setRotateYCenter((int)number(rotation,"centerY",0));component.setMatrixsNormal();
        if(bool(flip,"horizontal")||bool(flip,"vertical"))throw unsupported("좌우·상하 반전된 그림");
        if(child(source,"renderingInfo")!=null||rotation!=null)warnings.add("그림의 복잡한 변환 행렬은 원본 바이너리·폭·높이·회전각을 보존하여 근사했습니다.");
        if(child(source,"lineShape")!=null)warnings.add("그림 테두리는 HWP 기본 테두리로 근사했습니다.");
        if(number(image,"alpha",0)!=0)warnings.add("그림 투명도는 원본 바이너리를 보존하여 기본 불투명도로 근사했습니다.");
        ShapeComponentPicture data=picture.getShapeComponentPicture();data.getPictureInfo().setBinItemID(imageID);data.getPictureInfo().setBrightness((byte)number(image,"bright",0));data.getPictureInfo().setContrast((byte)number(image,"contrast",0));String effect=attr(image,"effect","REAL_PIC");data.getPictureInfo().setEffect("GRAY_SCALE".equals(effect)?kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PictureEffect.GrayScale:"BLACK_WHITE".equals(effect)?kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PictureEffect.BlackWhite:kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PictureEffect.RealPicture);data.setInstanceId(number(source,"instid",0));
        Element dim=child(source,"imgDim"),clip=child(source,"imgClip"),rectangle=child(source,"imgRect");data.setImageWidth(number(dim,"dimwidth",component.getWidthAtCreate()));data.setImageHeight(number(dim,"dimheight",component.getHeightAtCreate()));
        data.setLeftAfterCutting((int)number(clip,"left",0));data.setRightAfterCutting((int)number(clip,"right",data.getImageWidth()));data.setTopAfterCutting((int)number(clip,"top",0));data.setBottomAfterCutting((int)number(clip,"bottom",data.getImageHeight()));
        Element p0=child(rectangle,"pt0"),p1=child(rectangle,"pt1"),p2=child(rectangle,"pt2"),p3=child(rectangle,"pt3");data.getLeftTop().setX((int)number(p0,"x",0));data.getLeftTop().setY((int)number(p0,"y",0));data.getRightTop().setX((int)number(p1,"x",component.getWidthAtCreate()));data.getRightTop().setY((int)number(p1,"y",0));data.getRightBottom().setX((int)number(p2,"x",component.getWidthAtCreate()));data.getRightBottom().setY((int)number(p2,"y",component.getHeightAtCreate()));data.getLeftBottom().setX((int)number(p3,"x",0));data.getLeftBottom().setY((int)number(p3,"y",component.getHeightAtCreate()));
        pictureCount++;
    }

    private void sectionProperties(Element source,Paragraph p) throws Exception {
        checkHorizontal(source);
        ControlSectionDefine section=null;for(Control c:p.getControlList())if(c instanceof ControlSectionDefine)section=(ControlSectionDefine)c;
        if(section==null)throw new EngineException("HWP 구역 정의를 생성할 수 없습니다.");
        Element page=child(source,"pagePr"),margin=child(page,"margin");PageDef definition=section.getPageDef();
        if(page!=null){definition.setPaperWidth(number(page,"width",59528));definition.setPaperHeight(number(page,"height",84188));definition.getProperty().setPaperDirection("NARROWLY".equals(attr(page,"landscape","WIDELY"))?PaperDirection.Landscape:PaperDirection.Portrait);String gutter=attr(page,"gutterType","LEFT_ONLY");definition.getProperty().setMakingBookMethod("LEFT_RIGHT".equals(gutter)?MakingBookMethod.BothSideEditing:"TOP_BOTTOM".equals(gutter)?MakingBookMethod.BackFlip:MakingBookMethod.OneSideEditing);}
        if(margin!=null){definition.setLeftMargin(number(margin,"left",8504));definition.setRightMargin(number(margin,"right",8504));definition.setTopMargin(number(margin,"top",5668));definition.setBottomMargin(number(margin,"bottom",4252));definition.setHeaderMargin(number(margin,"header",4252));definition.setFooterMargin(number(margin,"footer",4252));definition.setGutterMargin(number(margin,"gutter",0));}
        section.getHeader().setDefaultTabGap(number(source,"tabStop",8000));section.getHeader().setColumnGap((int)number(source,"spaceColumns",0));
        for(Element c:children(source)){
            String tag=local(c);
            if("masterPage".equals(tag))throw unsupported("바탕쪽");
            if("pageBorderFill".equals(tag)){String type=attr(c,"type","BOTH");kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageBorderFill border="EVEN".equals(type)?section.getEvenPageBorderFill():"ODD".equals(type)?section.getOddPageBorderFill():section.getBothPageBorderFill();border.setBorderFillId(borderRef(attr(c,"borderFillIDRef","0")));Element gap=child(c,"offset");border.setLeftGap((int)number(gap,"left",0));border.setRightGap((int)number(gap,"right",0));border.setTopGap((int)number(gap,"top",0));border.setBottomGap((int)number(gap,"bottom",0));}
            else if(!Arrays.asList("pagePr","grid","startNum","visibility","lineNumberShape","footNotePr","endNotePr").contains(tag))throw unsupported("구역 설정 "+tag);
        }
        Element visibility=child(source,"visibility");if(visibility!=null){section.getHeader().getProperty().setHideHeader(bool(visibility,"hideFirstHeader"));section.getHeader().getProperty().setHideFooter(bool(visibility,"hideFirstFooter"));}
        Element start=child(source,"startNum");if(start!=null){section.getHeader().setPageStartNumber((int)number(start,"page",0));section.getHeader().setImageStartNumber((int)number(start,"pic",0));section.getHeader().setTableStartNumber((int)number(start,"tbl",0));section.getHeader().setEquationStartNumber((int)number(start,"equation",0));}
    }

    private static List<String> textInventory(HWPFile file) throws Exception {
        ArrayList<String> result=new ArrayList<String>();for(Section section:file.getBodyText().getSectionList())for(Paragraph p:section)inventory(p,result);return result;
    }
    private static void inventory(Paragraph p,List<String> result) throws Exception {
        StringBuilder text=new StringBuilder();
        if(p.getText()!=null)for(HWPChar character:p.getText().getCharList()){
            int code=character.getCode();
            if(character instanceof HWPCharNormal){if(code!=13)text.append((char)code);}
            else if(code==9||code==10||code==24||code==30||code==31)text.append((char)code);
        }
        result.add(text.toString());
        if(p.getControlList()==null)return;
        for(Control c:p.getControlList()){
            if(c instanceof ControlTable)for(Row row:((ControlTable)c).getRowList())for(Cell cell:row.getCellList())for(Paragraph child:cell.getParagraphList())inventory(child,result);
            else if(c instanceof ControlHeader)for(Paragraph child:((ControlHeader)c).getParagraphList())inventory(child,result);
            else if(c instanceof ControlFooter)for(Paragraph child:((ControlFooter)c).getParagraphList())inventory(child,result);
        }
    }
    private static Map<String,byte[]> binaryInventory(HWPFile file)throws EngineException{
        LinkedHashMap<String,byte[]> result=new LinkedHashMap<String,byte[]>();
        for(kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData data:file.getBinData().getEmbeddedBinaryDataList()){
            if(data.getName()==null||data.getData()==null||result.containsKey(data.getName()))throw new EngineException("그림 바이너리 이름·내용이 올바르지 않습니다.");
            result.put(data.getName(),data.getData().clone());
        }
        return result;
    }
    private static boolean equalBinaries(Map<String,byte[]> expected,Map<String,byte[]> actual){
        if(!expected.keySet().equals(actual.keySet()))return false;
        for(Map.Entry<String,byte[]> item:expected.entrySet())if(!Arrays.equals(item.getValue(),actual.get(item.getKey())))return false;
        return true;
    }
    private static void validateSectionNamespaces(Element root)throws EngineException{
        String ns=root.getNamespaceURI();
        if(ns==null||!Arrays.asList("http://www.hancom.co.kr/hwpml/2011/section","http://www.hancom.co.kr/hwpml/2011/paragraph","http://www.hancom.co.kr/hwpml/2016/paragraph","http://www.hancom.co.kr/hwpml/2011/core").contains(ns))throw unsupported("알 수 없는 XML 이름공간 "+ns+"/"+local(root));
        for(Element child:children(root))validateSectionNamespaces(child);
    }
    private static HeaderFooterApplyPage applyPage(String s){return "EVEN".equals(s)?HeaderFooterApplyPage.EvenPage:"ODD".equals(s)?HeaderFooterApplyPage.OddPage:HeaderFooterApplyPage.BothPage;}
    private static TextVerticalAlignment vertical(String s){return "CENTER".equals(s)?TextVerticalAlignment.Center:"BOTTOM".equals(s)?TextVerticalAlignment.Bottom:TextVerticalAlignment.Top;}
    private static RelativeArrange arrange(String s){return "CENTER".equals(s)?RelativeArrange.Center:("BOTTOM".equals(s)||"RIGHT".equals(s))?RelativeArrange.BottomOrRight:"INSIDE".equals(s)?RelativeArrange.Inside:"OUTSIDE".equals(s)?RelativeArrange.Outside:RelativeArrange.TopOrLeft;}
    private static void checkHorizontal(Element e) throws EngineException{if(e!=null&&!"HORIZONTAL".equals(attr(e,"textDirection","HORIZONTAL")))throw unsupported("세로쓰기");}
    private int borderRef(String id) throws EngineException{if("0".equals(id))return 0;return ref(borders,id,"테두리");}
    /** HwpUnitChar uses physical HWPUNITs; legacy HWP paragraph dimensions
     * use twice that number. Percentages are dimensionless in both branches. */
    private static final class ParagraphSetting {
        final Element element;
        final int scale;
        ParagraphSetting(Element element,int scale){this.element=element;this.scale=scale;}
    }
    private static ParagraphSetting paragraphSetting(Element root,String name){
        for(Element sw:children(root))if("switch".equals(local(sw)))for(Element branch:children(sw)){
            if(!"case".equals(local(branch)))continue;
            NamedNodeMap attributes=branch.getAttributes();
            for(int i=0;i<attributes.getLength();i++){
                Node attribute=attributes.item(i);
                if("required-namespace".equals(local(attribute))&&"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar".equals(attribute.getNodeValue())){
                    Element setting=child(branch,name);if(setting!=null)return new ParagraphSetting(setting,2);
                }
            }
        }
        Element direct=child(root,name);if(direct!=null)return new ParagraphSetting(direct,1);
        for(Element sw:children(root))if("switch".equals(local(sw))){Element setting=child(child(sw,"default"),name);if(setting!=null)return new ParagraphSetting(setting,1);}
        return new ParagraphSetting(null,1);
    }
    private static int marginValue(ParagraphSetting margin,String name)throws EngineException{return scaledParagraphValue(child(margin.element,name),"value",0,margin.scale);}
    private static int scaledParagraphValue(Element element,String name,long fallback,int scale)throws EngineException{
        long value=number(element,name,fallback);
        if(value<Integer.MIN_VALUE/scale||value>Integer.MAX_VALUE/scale)throw new EngineException("문단 간격이 HWP 수치 범위를 벗어납니다.");
        return(int)(value*scale);
    }
    private static int ref(Map<String,Integer> map,String id,String kind)throws EngineException{Integer result=map.get(id);if(result==null)throw new EngineException(kind+" 참조를 찾을 수 없습니다: "+id);return result;}
    private static void putUnique(Map<String,Integer> map,String id,int value,String kind)throws EngineException{if(map.put(id,value)!=null)throw new EngineException("중복된 "+kind+" ID: "+id);}
    private static String required(Element e,String name)throws EngineException{String value=attr(e,name,"");if(value.isEmpty())throw new EngineException("HWPX 필수 속성이 없습니다: "+local(e)+"/"+name);return value;}
    private static long number(Element e,String name,long fallback)throws EngineException{String value=attr(e,name,"");if(value.isEmpty())return fallback;try{return Long.parseLong(value);}catch(NumberFormatException ex){throw new EngineException("잘못된 HWPX 수치: "+local(e)+"/"+name+"="+value);}}
    private static boolean bool(Element e,String name){String s=attr(e,name,"");return "1".equals(s)||"true".equalsIgnoreCase(s);}
    private static String attr(Element e,String name,String fallback){return e==null||!e.hasAttribute(name)?fallback:e.getAttribute(name);}
    private static String local(Node e){return e==null?"":e.getLocalName()==null?e.getNodeName():e.getLocalName();}
    private static Element child(Element e,String name){if(e!=null)for(Element c:children(e))if(name.equals(local(c)))return c;return null;}
    private static List<Element> children(Element e){ArrayList<Element> result=new ArrayList<Element>();if(e!=null)for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element)result.add((Element)n);return result;}
    private static void color(String value,Color4Byte target)throws EngineException{if("none".equalsIgnoreCase(value)){target.setValue(0xFFFFFFFFL);return;}if(value.matches("#[0-9A-Fa-f]{8}"))value="#"+value.substring(3);if(!value.matches("#[0-9A-Fa-f]{6}"))throw new EngineException("잘못된 HWPX 색상: "+value);int rgb=Integer.parseInt(value.substring(1),16);target.setR((short)((rgb>>16)&255));target.setG((short)((rgb>>8)&255));target.setB((short)(rgb&255));target.setA((short)0);}
    private static EngineException unsupported(String detail){return new EngineException("이 HWPX의 "+detail+"은(는) 아직 HWP로 보존 변환할 수 없습니다. 내용 누락을 막기 위해 저장을 중단했습니다. 한컴에서 HWP로 저장하세요.");}
}
