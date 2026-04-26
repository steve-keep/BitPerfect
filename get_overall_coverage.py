import xml.etree.ElementTree as ET
import sys

def check_coverage(xml_file):
    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()

        components_missed = 0
        components_covered = 0

        for pkg in root.findall("package"):
            for cls in pkg.findall("class"):
                sourcefile = cls.attrib.get('sourcefilename')
                if sourcefile == "Components.kt":
                    for counter in cls.findall("counter"):
                        if counter.attrib["type"] == "LINE":
                            missed = int(counter.attrib["missed"])
                            covered = int(counter.attrib["covered"])
                            components_missed += missed
                            components_covered += covered

        if components_missed + components_covered > 0:
            print(f"Overall Components.kt coverage: {components_covered / (components_missed + components_covered) * 100}%")
        else:
            print("No lines found for Components.kt")

    except Exception as e:
        print(f"Error parsing {xml_file}: {e}")

check_coverage("app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
