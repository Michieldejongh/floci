# Cloud Control API

**Protocol:** JSON 1.1 (`X-Amz-Target: CloudApiService.*`)
**Endpoint:** `POST http://localhost:4566/`

Cloud Control provides a uniform CRUD(L) surface over CloudFormation resource types
(`AWS::S3::Bucket`, `AWS::EC2::Instance`, ...) instead of a bespoke API per service.
Floci's emulator reuses the same resource provisioner CloudFormation stacks call, so a
resource behaves consistently whether it's provisioned by a stack or directly through
Cloud Control.

## Supported Operations

| Operation | Description |
| --- | --- |
| `CreateResource` | Provision a resource of the given `TypeName` from a `DesiredState` JSON document |
| `UpdateResource` | Apply an RFC 6902 `PatchDocument` to a resource created through Cloud Control |
| `DeleteResource` | Delete a resource by `TypeName` and `Identifier` |
| `GetResource` | Read a resource's current properties by `TypeName` and `Identifier` |
| `ListResources` | List resources of a `TypeName` |
| `GetResourceRequestStatus` | Poll a `CreateResource`/`UpdateResource`/`DeleteResource` request by its token |

## Behavior

- **Asynchronous create/delete**: `CreateResource` returns an `IN_PROGRESS` `ProgressEvent`
  and a request token immediately; provisioning runs in the background. Poll
  `GetResourceRequestStatus` with the token until the status is `SUCCESS` or `FAILED`.
  `DeleteResource` is synchronous (deletes are fast enough not to need polling) but
  still returns a `ProgressEvent` for API-shape consistency.
- **`UpdateResource`**: the `PatchDocument` (all six RFC 6902 operations; a path may omit the
  leading `/`) is applied to the resource's current model and validated before anything runs. An
  invalid patch or a failed `test` fails with `InvalidRequestException`, a change to a createOnly
  property (from the CloudFormation registry schema) with `NotUpdatableException`, and an unknown
  identifier with `ResourceNotFoundException`. The changed properties are carried onto the desired
  state the resource was last provisioned from, and the type's CloudFormation provisioner runs its
  `UpdateStack` update path over the existing physical id, asynchronously like `CreateResource`
  (`Operation: UPDATE`). The new model and desired state are persisted, so `GetResource` and the
  next `UpdateResource` see them. Only resources created through Cloud Control can be updated;
  others fail with `UnsupportedActionException`. An update that would replace the resource under a
  new identifier fails rather than replacing it, as AWS leaves replacement to the client.
- **`ListResources` / `GetResource` type coverage**: the read side lists
  `AWS::S3::Bucket`, `AWS::EC2::VPC`, `AWS::EC2::Subnet`, `AWS::EC2::SecurityGroup`,
  `AWS::EC2::Instance`, `AWS::EC2::LaunchTemplate`, `AWS::IAM::Role`, `AWS::IAM::User`,
  and `AWS::IAM::InstanceProfile`. `CreateResource` accepts any type the underlying
  CloudFormation resource provisioner supports (the same set stacks can provision),
  which is broader than the read side. A resource created through Cloud Control but
  outside the read-side type list is still readable via `GetResource`, from
  create-time state, but will not appear in `ListResources`.
- **`ListResources` on an unlisted type**: `TypeName` values outside the list above
  fail with `UnsupportedActionException` (HTTP 400) instead of returning an empty
  `ResourceDescriptions`. This applies to any type Floci does not enumerate on the
  read side, whether or not the type is real in AWS, since Floci has no CloudFormation
  type registry to tell the two apart.
- **Delete needs create-time state**: types whose delete depends on attributes
  captured at create time (currently `AWS::EKS::Nodegroup` and `AWS::IAM::Policy`, plus
  any `Custom::*` / `AWS::CloudFormation::CustomResource`) fail `DeleteResource` with a
  clear error if that state isn't held, rather than reporting `SUCCESS` over a resource
  that's still there.
- **No account context**: Cloud Control requests don't carry an account id; Floci uses
  its default test account (`000000000000`) for all resources.
