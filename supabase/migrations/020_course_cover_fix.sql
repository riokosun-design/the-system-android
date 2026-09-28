-- 020 · COURSE COVER REPAIR (field report 0.13.1: Black Room hero plate blank)
-- Root cause: the generated art ships in the APK as the-black-room.webp but the
-- server row and the client fallback both pointed at black-room.webp — Coil
-- loaded nothing and the plate stayed solid gray. 13 further catalog rows had
-- image_url = NULL and fell back to non-existent slug files; map each to the
-- closest existing generated artwork. Art itself was already generated and
-- verified bundled — this is a pointer repair only, no new content.

update courses set image_url = 'file:///android_asset/courses/the-black-room.webp'
where slug = 'the-black-room';

update courses set image_url = case slug
    when 'adaptive-fighter'      then 'file:///android_asset/courses/adaptive-fighter-physique.webp'
    when 'black-swordsman'       then 'file:///android_asset/courses/functional-muscle-build.webp'
    when 'monarch-core-titan'    then 'file:///android_asset/courses/athletic-strength-build.webp'
    when 'wind-sprint-speed'     then 'file:///android_asset/courses/speed-development.webp'
    when 'anvil-punch-power'     then 'file:///android_asset/courses/punch-power-athletics.webp'
    when 'ram-leg-power'         then 'file:///android_asset/courses/leg-power-development.webp'
    when 'monarch-kick-mobility' then 'file:///android_asset/courses/mobility-training.webp'
    when 'shadow-agility'        then 'file:///android_asset/courses/agility-training.webp'
    when 'phantom-reflexes'      then 'file:///android_asset/courses/reflex-training.webp'
    when 'serpent-flexibility'   then 'file:///android_asset/courses/flexibility-training.webp'
    when 'iron-core-endurance'   then 'file:///android_asset/courses/endurance-training.webp'
    when 'iron-lungs-stamina'    then 'file:///android_asset/courses/stamina-conditioning.webp'
    when 'titan-grip'            then 'file:///android_asset/courses/grip-strength.webp'
    else image_url end
where image_url is null
  and slug in (
    'adaptive-fighter','black-swordsman','monarch-core-titan','wind-sprint-speed',
    'anvil-punch-power','ram-leg-power','monarch-kick-mobility','shadow-agility',
    'phantom-reflexes','serpent-flexibility','iron-core-endurance','iron-lungs-stamina',
    'titan-grip'
  );
