python3 -c "
import xml.etree.ElementTree as ET

tree = ET.parse('app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml')
root = tree.getroot()

changed_files = [
    'MainActivity.kt',
    'AppNavigation.kt',
    'AppViewModel.kt',
    'Components.kt',
    'SettingsScreen.kt',
    'TrackListScreen.kt'
]

total_missed = 0
total_covered = 0

for pkg in root.findall('package'):
    for clazz in pkg.findall('class'):
        filename = clazz.get('sourcefilename')
        if filename in changed_files:
            for counter in clazz.findall('counter'):
                if counter.get('type') == 'INSTRUCTION':
                    total_missed += int(counter.get('missed'))
                    total_covered += int(counter.get('covered'))

total = total_missed + total_covered
if total > 0:
    coverage = (total_covered / total) * 100
    print(f'Changed files coverage: {coverage:.2f}%')
else:
    print('No lines covered in changed files.')
"
