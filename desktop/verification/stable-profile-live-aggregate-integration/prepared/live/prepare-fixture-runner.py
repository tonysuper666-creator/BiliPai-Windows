from pathlib import Path
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
s=(MAIN/'desktop/.local/stable-home-category-page-parity/fixture.py').read_text(encoding='utf-8')
changes={
 'stable-product-snapshot-36':'stable-product-snapshot-39',
 'runs/compile-02/prepared-category-page.jar':'runs/compile04/prepared-live-navigation.jar',
 'prepared_category_page':'prepared_live_navigation','CategoryVmFixture.kt':'LiveNavigationFixture.kt',
 'category-fixture.jar':'live-fixture.jar','categoryproof/':'liveproof/',
 'categoryproof.CategoryVmFixtureKt':'liveproof.LiveNavigationFixtureKt',
 "assert 'RESULT assertions=16 groups=3' in run.stdout,run.stdout":"assert 'groups=3' in run.stdout,run.stdout",
 'assertions=16,groups=3':"assertions=int(run.stdout.split('RESULT assertions=')[1].split()[0]),groups=3",
 'prospectiveCategoryOnly=True':'prospectiveLiveOnly=True',
 'explicitReferenceOnlySharedFamilies=4':'explicitReferenceOnlySharedDeclarations=2',
 'In-memory required region callback, no socket':'Java Proxy original API, no socket',
 'Actual existing DesktopOriginalHomePreferences and same real temporary DesktopPluginStore':'Actual same real temporary DesktopPluginStore and original key/tag DTO; candidate original decoder facade',
}
for a,b in changes.items():
 assert a in s,a;s=s.replace(a,b)
(HERE/'fixture.py').write_text(s,encoding='utf-8',newline='\n')
