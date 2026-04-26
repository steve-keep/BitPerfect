import xml.etree.ElementTree as ET
import sys

def check_coverage(xml_file):
    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()

        for pkg in root.findall("package"):
            for cls in pkg.findall("class"):
                sourcefile = cls.attrib.get('sourcefilename')
                if sourcefile in ["MainActivity.kt", "Components.kt"]:
                    for counter in cls.findall("counter"):
                        if counter.attrib["type"] == "LINE":
                            missed = int(counter.attrib["missed"])
                            covered = int(counter.attrib["covered"])
                            print(f"{cls.attrib['name']}: {covered / (missed + covered) * 100}%")

    except Exception as e:
        print(f"Error parsing {xml_file}: {e}")

check_coverage("app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
