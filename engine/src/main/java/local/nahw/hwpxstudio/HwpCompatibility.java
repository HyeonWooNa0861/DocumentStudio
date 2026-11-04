package local.nahw.hwpxstudio;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineSpaceSort;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.HWPXObject;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.common.compatibility.Case;
import kr.dogfoot.hwpxlib.object.common.compatibility.InSwitchObject;
import kr.dogfoot.hwpxlib.object.common.compatibility.Switch;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineSpacingType;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.parapr.LineSpacing;

/** Local corrections applied after the pinned HWP-to-HWPX converter. */
final class HwpCompatibility {
    private HwpCompatibility() { }

    /** The upstream converter labels fixed spacing as a percentage. Keep its
     * modern half-unit and legacy values, correcting only the spacing type. */
    static void correctLineSpacing(HWPFile source, HWPXFile converted) {
        ObjectList<ParaPr> properties = converted.headerXMLFile().refList().paraProperties();
        if (properties == null) {
            if (!source.getDocInfo().getParaShapeList().isEmpty()) {
                throw new IllegalStateException("Converted paragraph properties are missing");
            }
            return;
        }
        for (ParaPr property : properties.items()) {
            // The pinned converter assigns each HWP ParaShape's list index as
            // the HWPX paragraph-property id, independently of list traversal.
            int index = Integer.parseInt(property.id());
            if (index < 0 || index >= source.getDocInfo().getParaShapeList().size()) {
                throw new IllegalStateException("Converted paragraph property has no HWP source");
            }
            ParaShape original = source.getDocInfo().getParaShapeList().get(index);
            if (original.getProperty1().getLineSpaceSort() != LineSpaceSort.FixedValue) continue;
            correct(property.lineSpacing());
            if (property.switchList() != null) {
                for (Switch branch : property.switchList()) correct(branch);
            }
        }
    }

    private static void correct(LineSpacing spacing) {
        if (spacing != null) spacing.type(LineSpacingType.FIXED);
    }

    private static void correct(Switch branch) {
        for (Case choice : branch.caseObjects()) correct(choice);
        correct(branch.defaultObject());
    }

    private static void correct(InSwitchObject branch) {
        if (branch == null) return;
        for (HWPXObject child : branch.children()) {
            if (child instanceof LineSpacing) correct((LineSpacing) child);
            else if (child instanceof Switch) correct((Switch) child);
        }
    }
}
