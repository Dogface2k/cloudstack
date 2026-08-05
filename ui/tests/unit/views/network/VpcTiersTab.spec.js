// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { flushPromises, shallowMount } from '@vue/test-utils'

import common from '../../../common'
import mockAxios from '../../../mock/mockAxios'
import TooltipButton from '@/components/widgets/TooltipButton'
import VpcTiersTab from '@/views/network/VpcTiersTab'

jest.mock('axios', () => mockAxios)
jest.mock('@/vue-app', () => ({
  vueProps: {
    $localStorage: {
      get: jest.fn(() => null)
    }
  }
}))

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

const aclListResponse = (id, name = id) => ({
  listnetworkacllistsresponse: {
    networkacllist: [{ id, name, description: `${name} description` }]
  }
})

const createContext = () => {
  const context = {
    resource: { id: 'vpc-id' },
    form: { acl: 'create-acl' },
    networkAclList: [{ id: 'create-acl', name: 'create ACL' }],
    selectedNetworkAcl: { id: 'create-acl', name: 'create ACL' },
    replaceAclFormRef: {
      value: {
        validate: jest.fn().mockResolvedValue(),
        scrollToField: jest.fn()
      }
    },
    replaceAclForm: {},
    replaceAclRules: {},
    replaceAclList: [],
    replaceAclSelected: {},
    replaceAclNetworkId: '',
    replaceAclLoading: false,
    replaceAclFetchLoading: false,
    replaceAclInteractionGeneration: 0,
    showReplaceAclModal: false,
    $t: key => key,
    $notifyError: jest.fn(),
    $pollJob: jest.fn(),
    parentFetchData: jest.fn()
  }
  Object.assign(context, VpcTiersTab.methods)
  return context
}

describe('Views > network > VpcTiersTab.vue', () => {
  beforeEach(() => {
    mockAxios.mockReset()
  })

  it('does not let a closed replacement request mutate create-tier state', async () => {
    const request = deferred()
    const context = createContext()
    mockAxios.mockImplementationOnce(() => request.promise)

    const openPromise = context.handleOpenReplaceAclModal({ id: 'tier-a', aclid: 'acl-a' })
    context.handleCloseReplaceAclModal()
    request.resolve(aclListResponse('acl-a'))
    await openPromise

    expect(context.form).toEqual({ acl: 'create-acl' })
    expect(context.networkAclList).toEqual([{ id: 'create-acl', name: 'create ACL' }])
    expect(context.selectedNetworkAcl).toEqual({ id: 'create-acl', name: 'create ACL' })
    expect(context.replaceAclList).toEqual([])
    expect(context.replaceAclSelected).toEqual({})
  })

  it('keeps the latest tier state when an older ACL request completes last', async () => {
    const requestA = deferred()
    const requestB = deferred()
    const context = createContext()
    mockAxios
      .mockImplementationOnce(() => requestA.promise)
      .mockImplementationOnce(() => requestB.promise)

    const openA = context.handleOpenReplaceAclModal({ id: 'tier-a', aclid: 'acl-a' })
    const openB = context.handleOpenReplaceAclModal({ id: 'tier-b', aclid: 'acl-b' })

    requestB.resolve(aclListResponse('acl-b', 'ACL B'))
    await openB
    requestA.resolve(aclListResponse('acl-a', 'ACL A'))
    await openA

    expect(context.replaceAclNetworkId).toBe('tier-b')
    expect(context.replaceAclForm.acl).toBe('acl-b')
    expect(context.replaceAclList).toEqual([{
      id: 'acl-b',
      name: 'ACL B',
      description: 'ACL B description'
    }])
    expect(context.replaceAclSelected.id).toBe('acl-b')
    expect(context.replaceAclFetchLoading).toBe(false)
  })

  it('does not submit after the interaction is closed during validation', async () => {
    const validation = deferred()
    const context = createContext()
    context.replaceAclInteractionGeneration = 1
    context.replaceAclNetworkId = 'tier-a'
    context.replaceAclForm = { acl: 'acl-a' }
    context.replaceAclFormRef = {
      value: {
        validate: jest.fn(() => validation.promise),
        scrollToField: jest.fn()
      }
    }
    context.showReplaceAclModal = true

    const submission = context.handleReplaceAclSubmit()
    context.handleCloseReplaceAclModal()
    validation.resolve()
    await submission

    expect(mockAxios).not.toHaveBeenCalled()
    expect(context.$pollJob).not.toHaveBeenCalled()
    expect(context.replaceAclLoading).toBe(false)
  })

  it('submits captured IDs without clearing a newer replacement interaction', async () => {
    const firstSubmissionRequest = deferred()
    const nextAclRequest = deferred()
    const nextSubmissionRequest = deferred()
    const context = createContext()
    context.replaceAclInteractionGeneration = 1
    context.replaceAclNetworkId = 'tier-a'
    context.replaceAclForm = { acl: 'acl-a' }
    context.replaceAclFormRef = {
      value: {
        validate: jest.fn().mockResolvedValue(),
        scrollToField: jest.fn()
      }
    }
    context.showReplaceAclModal = true
    mockAxios
      .mockImplementationOnce(() => firstSubmissionRequest.promise)
      .mockImplementationOnce(() => nextAclRequest.promise)
      .mockImplementationOnce(() => nextSubmissionRequest.promise)

    const firstSubmission = context.handleReplaceAclSubmit()
    await flushPromises()

    const firstPostRequest = mockAxios.mock.calls[0][0]
    expect(firstPostRequest.method).toBe('POST')
    expect(firstPostRequest.data.get('command')).toBe('replaceNetworkACLList')
    expect(firstPostRequest.data.get('aclid')).toBe('acl-a')
    expect(firstPostRequest.data.get('networkid')).toBe('tier-a')

    const nextInteraction = context.handleOpenReplaceAclModal({ id: 'tier-b', aclid: 'acl-b' })
    expect(context.replaceAclLoading).toBe(false)
    expect(context.replaceAclFetchLoading).toBe(true)

    nextAclRequest.resolve(aclListResponse('acl-b', 'ACL B'))
    await nextInteraction
    expect(context.replaceAclFetchLoading).toBe(false)
    context.replaceAclFormRef.value = {
      validate: jest.fn().mockResolvedValue(),
      scrollToField: jest.fn()
    }

    const nextSubmission = context.handleReplaceAclSubmit()
    await flushPromises()

    const nextPostRequest = mockAxios.mock.calls[2][0]
    expect(nextPostRequest.method).toBe('POST')
    expect(nextPostRequest.data.get('aclid')).toBe('acl-b')
    expect(nextPostRequest.data.get('networkid')).toBe('tier-b')
    expect(context.replaceAclLoading).toBe(true)

    firstSubmissionRequest.resolve({ replacenetworkacllistresponse: { jobid: 'job-a' } })
    await firstSubmission

    expect(context.$pollJob).toHaveBeenCalledWith(expect.objectContaining({
      jobId: 'job-a',
      description: 'tier-a'
    }))
    expect(context.replaceAclNetworkId).toBe('tier-b')
    expect(context.replaceAclLoading).toBe(true)

    nextSubmissionRequest.resolve({ replacenetworkacllistresponse: { jobid: 'job-b' } })
    await nextSubmission
    expect(context.replaceAclLoading).toBe(false)
  })

  it('keeps the overview replacement action disabled without API permission', async () => {
    mockAxios.mockImplementation(request => {
      const command = request.params?.command
      switch (command) {
        case 'listZones':
          return Promise.resolve({ listzonesresponse: { zone: [{}] } })
        case 'listVPCOfferings':
          return Promise.resolve({ listvpcofferingsresponse: { vpcoffering: [{}] } })
        case 'listLoadBalancers':
          return Promise.resolve({ listloadbalancersresponse: { loadbalancer: [], count: 0 } })
        case 'listVirtualMachines':
          return Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: [], count: 0 } })
        case 'listNetworkOfferings':
          return Promise.resolve({ listnetworkofferingsresponse: { networkoffering: [{ supportsinternallb: false }] } })
        case 'listNetworks':
          return Promise.resolve({ listnetworksresponse: { network: [] } })
        default:
          return Promise.resolve({})
      }
    })
    const store = common.createMockStore({
      user: {
        apis: {},
        info: {}
      }
    })
    const wrapper = shallowMount(VpcTiersTab, {
      props: {
        resource: {
          id: 'vpc-id',
          zoneid: 'zone-id',
          vpcofferingid: 'vpc-offering-id',
          network: [{
            id: 'tier-id',
            zoneid: 'zone-id',
            networkofferingid: 'network-offering-id',
            name: 'tier',
            state: 'Implemented',
            cidr: '10.0.0.0/24',
            aclid: 'acl-id',
            aclname: 'ACL',
            service: []
          }]
        }
      },
      global: {
        plugins: [store],
        mocks: {
          $t: key => key,
          $router: {
            push: jest.fn()
          }
        },
        provide: {
          parentFetchData: jest.fn()
        }
      }
    })

    await flushPromises()
    const replaceButton = wrapper.findAllComponents(TooltipButton)
      .find(button => button.props('tooltip') === 'label.replace.acl')

    expect(replaceButton).toBeDefined()
    expect(replaceButton.props('disabled')).toBe(true)
    wrapper.unmount()
  })
})
