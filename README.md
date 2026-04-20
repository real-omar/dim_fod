status: working on permissive

patch by patch by: https://github.com/n-bilinskyi

currently im facing selinux denial when setprop but on permissive it works fine 

system/bin/init: type=1107 audit(0.0:3111): uid=0 auid=4294967295 ses=4294967295 subj=u:r:init:s0 msg='avc:  denied  { set } for property=sys.phh.oplus.fppress pid=2840 uid=10205 gid=10205 scontext=u:r:platform_app:s0:c512,c768 tcontext=u:object_r:system_prop:s0 tclass=property_service permissive=0'
