# Openstack API Extractor [![license](http://img.shields.io/:license-MIT-blue.svg)](LICENSE)
[![Build Status](https://travis-ci.org/ist-dsi/openstack-api-extractor.svg?branch=master&style=plastic&maxAge=604800)](https://travis-ci.org/ist-dsi/openstack-api-extractor)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/a72c1eddf1ff4d82a2be0e2a5dc21272)](https://www.codacy.com/app/IST-DSI/openstack-api-extractor?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ist-dsi/openstack-api-extractor&amp;utm_campaign=Badge_Grade)
[![BCH compliance](https://bettercodehub.com/edge/badge/ist-dsi/openstack-api-extractor)](https://bettercodehub.com/results/ist-dsi/openstack-api-extractor)

This project parses the Openstack developer documentation pages and creates the corresponding Openapi 3.0.1 yaml specification files.
It ignores all the deprecated and obsolete APIs.

Currently the following APIs are processed: glance (images), designate (dns), keystone (identity), cinder (block storage), nova (compute) and neutron (network).

The Openstack API documentation pages have a lot of syntax errors, to the point of Jsoup not being able to handle it, even
tough its parser is very lenient. To overcome that issue we asked Chromium to format the page source and then saved each page
in a file.

The saved files were obtained in these locations at 2019-03-08.

 - https://developer.openstack.org/api-ref/image/v2/
 - https://developer.openstack.org/api-ref/dns/
 - https://developer.openstack.org/api-ref/identity/v3/
 - https://developer.openstack.org/api-ref/block-storage/v3/index.html
 - https://developer.openstack.org/api-ref/compute/
 - https://developer.openstack.org/api-ref/network/v2/