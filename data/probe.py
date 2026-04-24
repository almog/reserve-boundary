import shapefile

sf = shapefile.Reader("extracted/respark_meforat_14-10-2020.shp", encoding="utf-8")
print("shape type:", sf.shapeTypeName)
print("num records:", len(sf))
print("\nfields:")
for f in sf.fields:
    print(" ", f)

print("\nfirst 3 records (attributes):")
for i, rec in enumerate(sf.records()[:3]):
    print(" ", dict(rec.as_dict()))

print("\nbbox of first shape (native ITM):", sf.shape(0).bbox)
print("overall bbox:", sf.bbox)

status_field = None
type_field = None
for f in sf.fields[1:]:
    name = f[0]
    if "STATUS" in name.upper() or "MATZAV" in name.upper() or "MEUSHAR" in name.upper():
        status_field = name
    if "TYPE" in name.upper() or "SUG" in name.upper() or "KIND" in name.upper():
        type_field = name

print("\nlikely status field:", status_field)
print("likely type field:", type_field)

from collections import Counter
if status_field:
    print("status values:", Counter(r[status_field] for r in sf.records()).most_common())
if type_field:
    print("type values:", Counter(r[type_field] for r in sf.records()).most_common())
